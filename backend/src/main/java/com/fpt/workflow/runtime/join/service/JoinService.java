package com.fpt.workflow.runtime.join.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.runtime.activation.ActivationKey;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.join.domain.JoinArrivedBranch;
import com.fpt.workflow.runtime.join.domain.JoinPolicy;
import com.fpt.workflow.runtime.join.domain.JoinState;
import com.fpt.workflow.runtime.join.repository.JoinArrivedBranchRepository;
import com.fpt.workflow.runtime.join.repository.JoinStateRepository;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages Join synchronization lifecycle across concurrent branches. Guarantees durable tracking of
 * arrived branches, condition evaluation (AND, FIRST, N_OF_M), waiting state maintenance, and
 * exactly-once single downstream continuation.
 */
@Service
public class JoinService {

  private static final Logger LOGGER = LoggerFactory.getLogger(JoinService.class);

  private final JoinStateRepository stateRepository;
  private final JoinArrivedBranchRepository branchRepository;
  private final NodeExecutionRepository executionRepository;
  private final EdgeDefinitionRepository edgeRepository;
  private final EventRepository eventRepository;
  private final RoutingService routingService;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;

  public JoinService(
      JoinStateRepository stateRepository,
      JoinArrivedBranchRepository branchRepository,
      NodeExecutionRepository executionRepository,
      EdgeDefinitionRepository edgeRepository,
      EventRepository eventRepository,
      @Lazy RoutingService routingService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper) {
    this.stateRepository = stateRepository;
    this.branchRepository = branchRepository;
    this.executionRepository = executionRepository;
    this.edgeRepository = edgeRepository;
    this.eventRepository = eventRepository;
    this.routingService = routingService;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  public boolean isJoin(NodeDefinition node) {
    if (node == null) return false;
    if ("JOIN".equalsIgnoreCase(node.getNodeType())) return true;
    if (node.getConfigJson() != null && node.getConfigJson().hasNonNull("join")) return true;
    return false;
  }

  @Transactional
  public NodeExecution arrive(
      Event event,
      NodeDefinition joinNode,
      NodeExecution inboundExecution,
      UUID inboundEdgeId,
      UUID joinScopeId,
      CorrelationId correlationId,
      CommandId commandId) {

    UUID scopeId = joinScopeId != null ? joinScopeId : inboundExecution.getJoinScopeId();
    if (scopeId == null) {
      scopeId = joinNode.getId();
    }

    Instant now = clock.now();

    // 1. Pessimistic lock on JoinState for this (event, joinNode, joinScopeId)
    Optional<JoinState> stateOpt =
        stateRepository.findByScopeForUpdate(event.getId(), joinNode.getId(), scopeId);

    JoinState state;
    NodeExecution joinExecution;

    if (stateOpt.isEmpty()) {
      JoinPolicy policy = parseJoinPolicy(joinNode);
      int requiredCount = calculateRequiredCount(event.getWorkflowVersionId(), joinNode, policy);

      String parentPath = parentPath(inboundExecution.getPathToken());

      ActivationKey joinKey =
          ActivationKey.downstream(
              event.getId(),
              joinNode.getId(),
              scopeId,
              parentPath,
              inboundExecution.getCycleId(),
              null);

      Optional<NodeExecution> existingExec =
          executionRepository.findByActivationKey(joinKey.value());

      if (existingExec.isPresent()) {
        joinExecution = existingExec.get();
      } else {
        joinExecution =
            NodeExecution.create(
                uuidGenerator.generate(),
                event.getId(),
                joinNode.getId(),
                joinKey.value(),
                inboundExecution.getCycleId(),
                0,
                parentPath,
                null,
                inboundExecution.getSplitScopeId(),
                scopeId,
                objectMapper.createObjectNode(),
                event.getStartedTicketRevisionId(),
                now);
        joinExecution.markReady();
        joinExecution.start(now);
        joinExecution.waitFor(RuntimeWaitReason.JOIN);
        joinExecution = executionRepository.saveAndFlush(joinExecution);
      }

      state =
          stateRepository.save(
              JoinState.create(
                  uuidGenerator.generate(),
                  event.getId(),
                  joinNode.getId(),
                  scopeId,
                  policy,
                  requiredCount,
                  joinExecution.getId(),
                  now));
    } else {
      state = stateOpt.get();
      joinExecution =
          executionRepository.findByIdForUpdate(state.getJoinNodeExecutionId()).orElseThrow();
    }

    // 2. Duplicate arrival idempotency check
    Optional<JoinArrivedBranch> existingBranch =
        branchRepository.findByJoinStateIdAndInboundExecutionId(
            state.getId(), inboundExecution.getId());
    if (existingBranch.isPresent()) {
      LOGGER.info(
          "Inbound execution {} already arrived at join state {}, ignoring duplicate arrival",
          inboundExecution.getId(),
          state.getId());
      return joinExecution;
    }

    // 3. Record new arrival
    branchRepository.save(
        JoinArrivedBranch.create(
            uuidGenerator.generate(), state.getId(), inboundExecution.getId(), inboundEdgeId, now));

    boolean thresholdReached = state.recordArrival(now);
    state = stateRepository.save(state);

    // 4. Condition evaluation and exactly-once downstream routing
    if (thresholdReached) {
      if (state.markRoutedDownstream()) {
        state.markCompleted(now);
        state = stateRepository.save(state);

        if (joinExecution.getStatus() != NodeExecutionStatus.COMPLETED) {
          ObjectNode aggOutput = objectMapper.createObjectNode();
          aggOutput.put("arrivedCount", state.getArrivedCount());
          aggOutput.put("requiredCount", state.getRequiredCount());
          aggOutput.put("policy", state.getJoinPolicy().name());
          joinExecution.complete("DEFAULT", aggOutput, now);
          joinExecution = executionRepository.saveAndFlush(joinExecution);
        }

        routingService.route(joinExecution.getId(), correlationId, commandId);
      }
    } else {
      if (joinExecution.getStatus() != NodeExecutionStatus.WAITING) {
        joinExecution.waitFor(RuntimeWaitReason.JOIN);
        joinExecution = executionRepository.saveAndFlush(joinExecution);
      }
      if (event.getStatus() != EventStatus.WAITING) {
        event.waitFor(RuntimeWaitReason.JOIN);
        eventRepository.save(event);
      }
    }

    return joinExecution;
  }

  private JoinPolicy parseJoinPolicy(NodeDefinition node) {
    JsonNode config = node.getConfigJson();
    if (config == null) return JoinPolicy.AND;
    if (config.hasNonNull("join") && config.get("join").hasNonNull("policy")) {
      return JoinPolicy.fromString(config.get("join").get("policy").asText());
    }
    if (config.hasNonNull("policy")) {
      return JoinPolicy.fromString(config.get("policy").asText());
    }
    return JoinPolicy.AND;
  }

  private int calculateRequiredCount(UUID versionId, NodeDefinition node, JoinPolicy policy) {
    if (policy == JoinPolicy.FIRST) return 1;

    JsonNode config = node.getConfigJson();
    if (config != null) {
      if (config.hasNonNull("join") && config.get("join").hasNonNull("threshold")) {
        return config.get("join").get("threshold").asInt();
      }
      if (config.hasNonNull("threshold")) {
        return config.get("threshold").asInt();
      }
    }

    List<EdgeDefinition> inboundEdges =
        edgeRepository.findAllByWorkflowVersionIdAndTargetNodeId(versionId, node.getId());
    return Math.max(1, inboundEdges.size());
  }

  private String parentPath(String path) {
    if (path == null || path.isBlank()) return "root";
    int lastSlash = path.lastIndexOf('/');
    if (lastSlash > 0) {
      return path.substring(0, lastSlash);
    }
    return "root";
  }
}
