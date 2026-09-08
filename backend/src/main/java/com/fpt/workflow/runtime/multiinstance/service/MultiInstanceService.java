package com.fpt.workflow.runtime.multiinstance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.multiinstance.domain.ExecutionMode;
import com.fpt.workflow.runtime.multiinstance.domain.MultiInstanceConfig;
import com.fpt.workflow.runtime.multiinstance.domain.MultiInstanceState;
import com.fpt.workflow.runtime.multiinstance.domain.NodeItemExecution;
import com.fpt.workflow.runtime.multiinstance.domain.RemainingItemPolicy;
import com.fpt.workflow.runtime.multiinstance.repository.MultiInstanceStateRepository;
import com.fpt.workflow.runtime.multiinstance.repository.NodeItemExecutionRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages multi-instance node execution lifecycle: collection expansion, item instantiation,
 * parallel/sequential execution, threshold evaluation, and downstream routing with exactly-once
 * delivery.
 */
@Service
public class MultiInstanceService {

  private static final Logger LOGGER = LoggerFactory.getLogger(MultiInstanceService.class);

  private final MultiInstanceStateRepository stateRepository;
  private final NodeItemExecutionRepository itemRepository;
  private final NodeExecutionRepository executionRepository;
  private final RoutingService routingService;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;

  public MultiInstanceService(
      MultiInstanceStateRepository stateRepository,
      NodeItemExecutionRepository itemRepository,
      NodeExecutionRepository executionRepository,
      @Lazy RoutingService routingService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper) {
    this.stateRepository = stateRepository;
    this.itemRepository = itemRepository;
    this.executionRepository = executionRepository;
    this.routingService = routingService;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  /** Checks whether the node definition config declares multi-instance execution. */
  public boolean isMultiInstance(NodeDefinition node) {
    if (node == null || node.getConfigJson() == null) return false;
    return node.getConfigJson().hasNonNull("multiInstance");
  }

  /**
   * Initializes multi-instance state and item executions for an activated node occurrence.
   *
   * @return initialized MultiInstanceState
   */
  @Transactional
  public MultiInstanceState initialize(
      Event event,
      NodeExecution execution,
      NodeDefinition node,
      EventContext context,
      CorrelationId correlationId,
      CommandId commandId) {

    // Replay idempotency: if state already exists, return it
    Optional<MultiInstanceState> existing =
        stateRepository.findByNodeExecutionId(execution.getId());
    if (existing.isPresent()) {
      return existing.get();
    }

    JsonNode miJson = node.getConfigJson().get("multiInstance");
    MultiInstanceConfig config = MultiInstanceConfig.fromJson(miJson);
    if (config == null) {
      throw new IllegalArgumentException(
          "Invalid multiInstance configuration on node " + node.getId());
    }

    List<JsonNode> collectionItems = extractCollection(config.collectionPath(), context);
    int totalItems = collectionItems.size();
    Instant now = clock.now();

    if (totalItems == 0) {
      // Empty collection: complete parent immediately with empty outcome
      MultiInstanceState state =
          stateRepository.save(
              MultiInstanceState.create(
                  uuidGenerator.generate(),
                  event.getId(),
                  execution.getId(),
                  config.executionMode(),
                  1, // dummy minimum for constraint
                  config.completionPolicy(),
                  config.completionThreshold(),
                  config.remainingItemPolicy(),
                  now));
      state.markCompleted(now);
      state.markRoutedDownstream();
      state = stateRepository.save(state);

      execution.complete("DEFAULT", objectMapper.createObjectNode(), now);
      executionRepository.saveAndFlush(execution);
      routingService.route(execution.getId(), correlationId, commandId);
      return state;
    }

    MultiInstanceState state =
        stateRepository.save(
            MultiInstanceState.create(
                uuidGenerator.generate(),
                event.getId(),
                execution.getId(),
                config.executionMode(),
                totalItems,
                config.completionPolicy(),
                config.completionThreshold(),
                config.remainingItemPolicy(),
                now));

    List<NodeItemExecution> items = new ArrayList<>();
    for (int i = 0; i < totalItems; i++) {
      String itemToken = "item-" + i;
      NodeItemExecution item =
          NodeItemExecution.create(
              uuidGenerator.generate(),
              state.getId(),
              event.getId(),
              execution.getId(),
              i,
              itemToken,
              collectionItems.get(i),
              now);
      if (config.executionMode() == ExecutionMode.PARALLEL || i == 0) {
        item.markRunning(now);
      }
      items.add(itemRepository.save(item));
    }

    return state;
  }

  /**
   * Completes an item within a multi-instance node execution. Thread-safe and idempotent via
   * pessimistic lock on MultiInstanceState.
   *
   * @return Optional RoutingResult if this completion triggered downstream routing.
   */
  @Transactional
  public Optional<RoutingResult> completeItem(
      UUID parentExecutionId,
      int itemIndex,
      String outcomePort,
      JsonNode outputJson,
      CorrelationId correlationId,
      CommandId commandId) {

    MultiInstanceState state =
        stateRepository
            .findByNodeExecutionIdForUpdate(parentExecutionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "MultiInstanceState not found for node execution: " + parentExecutionId));

    NodeItemExecution item =
        itemRepository
            .findByParentNodeExecutionIdAndItemIndex(parentExecutionId, itemIndex)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "NodeItemExecution not found for execution: "
                            + parentExecutionId
                            + " index: "
                            + itemIndex));

    // Duplicate completion idempotency
    if ("COMPLETED".equals(item.getStatus()) || "CANCELLED".equals(item.getStatus())) {
      LOGGER.info(
          "Item {} for execution {} is already {}, ignoring replay",
          itemIndex,
          parentExecutionId,
          item.getStatus());
      return Optional.empty();
    }

    Instant now = clock.now();
    item.complete(outcomePort != null ? outcomePort : "DEFAULT", outputJson, now);
    itemRepository.save(item);

    boolean thresholdReached = state.recordItemCompletion(now);
    Optional<RoutingResult> routingResult = Optional.empty();

    if (thresholdReached) {
      if (state.markRoutedDownstream()) {
        // First time threshold reached: route downstream exactly once!
        if (state.getRemainingItemPolicy() == RemainingItemPolicy.CANCEL_REMAINING) {
          cancelRemainingItems(state.getId(), now);
          state.markCompleted(now);
        } else {
          // KEEP_RUNNING: only mark COMPLETED when all items finish
          if (state.getCompletedItems() >= state.getTotalItems()) {
            state.markCompleted(now);
          }
        }
        stateRepository.save(state);

        NodeExecution parent =
            executionRepository.findByIdForUpdate(parentExecutionId).orElseThrow();
        if (parent.getStatus()
            != com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus.COMPLETED) {
          // Aggregate output
          ObjectNode aggOutput = objectMapper.createObjectNode();
          aggOutput.put("completedItems", state.getCompletedItems());
          aggOutput.put("totalItems", state.getTotalItems());
          parent.complete(outcomePort != null ? outcomePort : "DEFAULT", aggOutput, now);
          executionRepository.saveAndFlush(parent);
        }

        routingResult =
            Optional.of(routingService.route(parentExecutionId, correlationId, commandId));
      } else {
        // Subsequent completion under KEEP_RUNNING
        if (state.getCompletedItems() >= state.getTotalItems()) {
          state.markCompleted(now);
        }
        stateRepository.save(state);
      }
    } else {
      // Threshold not yet reached
      if (state.getExecutionMode() == ExecutionMode.SEQUENTIAL) {
        int nextIndex = itemIndex + 1;
        if (nextIndex < state.getTotalItems()) {
          itemRepository
              .findByParentNodeExecutionIdAndItemIndex(parentExecutionId, nextIndex)
              .ifPresent(
                  nextItem -> {
                    nextItem.markRunning(now);
                    itemRepository.save(nextItem);
                  });
        }
      }
      stateRepository.save(state);
    }

    return routingResult;
  }

  private void cancelRemainingItems(UUID stateId, Instant now) {
    List<NodeItemExecution> allItems =
        itemRepository.findAllByMultiInstanceStateIdOrderByItemIndexAsc(stateId);
    for (NodeItemExecution it : allItems) {
      if ("PENDING".equals(it.getStatus()) || "RUNNING".equals(it.getStatus())) {
        it.cancel(now);
        itemRepository.save(it);
      }
    }
  }

  private List<JsonNode> extractCollection(String path, EventContext context) {
    JsonNode root = context.value();
    if (root == null) return List.of();

    JsonNode current = root;
    String[] segments = path.split("\\.");
    for (String segment : segments) {
      if (current == null) return List.of();
      current = current.path(segment);
    }

    if (current == null || !current.isArray()) {
      return List.of();
    }

    List<JsonNode> items = new ArrayList<>();
    current.forEach(items::add);
    return List.copyOf(items);
  }
}
