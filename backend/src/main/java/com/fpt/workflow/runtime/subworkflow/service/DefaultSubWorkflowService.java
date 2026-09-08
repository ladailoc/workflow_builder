package com.fpt.workflow.runtime.subworkflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.nodetype.NodeExecutionResult;
import com.fpt.workflow.nodetype.WaitDescriptor;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.context.RuntimeScope;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.lifecycle.EventLifecycleService;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowCancellationPolicy;
import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowExecution;
import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowExecutionMode;
import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowExecutionStatus;
import com.fpt.workflow.runtime.subworkflow.repository.SubWorkflowExecutionRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultSubWorkflowService implements SubWorkflowService {

  private static final Logger log = LoggerFactory.getLogger(DefaultSubWorkflowService.class);

  private final WorkflowDefinitionRepository workflowDefinitionRepository;
  private final WorkflowVersionRepository workflowVersionRepository;
  private final NodeDefinitionRepository nodeDefinitionRepository;
  private final EventRepository eventRepository;
  private final NodeExecutionRepository nodeExecutionRepository;
  private final SubWorkflowExecutionRepository subWorkflowExecutionRepository;
  private final NodeActivationService activationService;
  private final RoutingService routingService;
  private final EventLifecycleService eventLifecycleService;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;

  public DefaultSubWorkflowService(
      WorkflowDefinitionRepository workflowDefinitionRepository,
      WorkflowVersionRepository workflowVersionRepository,
      NodeDefinitionRepository nodeDefinitionRepository,
      EventRepository eventRepository,
      NodeExecutionRepository nodeExecutionRepository,
      SubWorkflowExecutionRepository subWorkflowExecutionRepository,
      @Lazy NodeActivationService activationService,
      @Lazy RoutingService routingService,
      @Lazy EventLifecycleService eventLifecycleService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper) {
    this.workflowDefinitionRepository =
        Objects.requireNonNull(workflowDefinitionRepository, "workflowDefinitionRepository");
    this.workflowVersionRepository =
        Objects.requireNonNull(workflowVersionRepository, "workflowVersionRepository");
    this.nodeDefinitionRepository =
        Objects.requireNonNull(nodeDefinitionRepository, "nodeDefinitionRepository");
    this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
    this.nodeExecutionRepository =
        Objects.requireNonNull(nodeExecutionRepository, "nodeExecutionRepository");
    this.subWorkflowExecutionRepository =
        Objects.requireNonNull(subWorkflowExecutionRepository, "subWorkflowExecutionRepository");
    this.activationService = Objects.requireNonNull(activationService, "activationService");
    this.routingService = Objects.requireNonNull(routingService, "routingService");
    this.eventLifecycleService =
        Objects.requireNonNull(eventLifecycleService, "eventLifecycleService");
    this.uuidGenerator = Objects.requireNonNull(uuidGenerator, "uuidGenerator");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public boolean isSubWorkflowNode(NodeDefinition node) {
    return node != null && "SUB_WORKFLOW".equalsIgnoreCase(node.getNodeType());
  }

  @Override
  @Transactional
  public NodeExecutionResult activateSubWorkflow(
      Event parentEvent,
      NodeDefinition node,
      NodeExecution parentNodeExecution,
      JsonNode input,
      ActivationRequest request,
      RuntimeScope scope) {
    Instant now = clock.now();
    JsonNode config = node.getConfigJson();

    // 1. Resolve child WorkflowDefinition
    String rawChildKey = config.path("childWorkflowDefinitionKey").asText(null);
    if (rawChildKey == null || rawChildKey.isBlank()) {
      rawChildKey = config.path("childWorkflowKey").asText(null);
    }
    final String childDefKey = rawChildKey;
    String childDefIdStr = config.path("childWorkflowDefinitionId").asText(null);

    WorkflowDefinition childDef;
    if (childDefKey != null && !childDefKey.isBlank()) {
      childDef =
          workflowDefinitionRepository
              .findByKey(childDefKey)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Child workflow definition not found with key: " + childDefKey));
    } else if (childDefIdStr != null && !childDefIdStr.isBlank()) {
      UUID childDefId = UUID.fromString(childDefIdStr);
      childDef =
          workflowDefinitionRepository
              .findById(childDefId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Child workflow definition not found with id: " + childDefId));
    } else {
      throw new IllegalStateException(
          "SubWorkflow node configuration missing childWorkflowDefinitionKey or childWorkflowDefinitionId");
    }

    if (childDef.getLifecycle() != WorkflowDefinitionLifecycle.ACTIVE) {
      throw new IllegalStateException(
          "Child workflow definition is not ACTIVE (current="
              + childDef.getLifecycle()
              + "): "
              + childDef.getKey());
    }

    // 2. CRITICAL VERSION RULE: Resolve CURRENT PUBLISHED child WorkflowVersion at ACTIVATION time
    UUID publishedVersionId = childDef.getCurrentPublishedVersionId();
    if (publishedVersionId == null) {
      throw new IllegalStateException(
          "Child workflow definition '" + childDef.getKey() + "' has no published version");
    }
    WorkflowVersion childVersion =
        workflowVersionRepository
            .findById(publishedVersionId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Published child WorkflowVersion not found: " + publishedVersionId));
    if (childVersion.getStatus() != WorkflowVersionStatus.PUBLISHED) {
      throw new IllegalStateException(
          "Current published version is not in PUBLISHED status: " + publishedVersionId);
    }

    // 3. Execution Mode and Cancellation Policy
    String modeStr = config.path("executionMode").asText("WAIT_FOR_COMPLETION");
    SubWorkflowExecutionMode executionMode =
        "FIRE_AND_CONTINUE".equalsIgnoreCase(modeStr)
            ? SubWorkflowExecutionMode.FIRE_AND_CONTINUE
            : SubWorkflowExecutionMode.WAIT_FOR_COMPLETION;

    String cancelStr = config.path("cancellationPolicy").asText(null);
    SubWorkflowCancellationPolicy cancellationPolicy;
    if (cancelStr != null && !cancelStr.isBlank()) {
      cancellationPolicy = SubWorkflowCancellationPolicy.valueOf(cancelStr.toUpperCase());
    } else {
      cancellationPolicy =
          executionMode == SubWorkflowExecutionMode.WAIT_FOR_COMPLETION
              ? SubWorkflowCancellationPolicy.PROPAGATE
              : SubWorkflowCancellationPolicy.DETACH;
    }

    // 4. Input variable mapping (typed, non-shared mutable context)
    ObjectNode childVariables = objectMapper.createObjectNode();
    JsonNode inputMappings = config.path("inputMappings");
    if (inputMappings.isObject()) {
      inputMappings
          .fields()
          .forEachRemaining(
              entry -> {
                String targetKey = entry.getKey();
                JsonNode sourceVal = entry.getValue();
                if (sourceVal.isTextual()
                    && sourceVal.asText().startsWith("${")
                    && sourceVal.asText().endsWith("}")) {
                  String expr = sourceVal.asText().substring(2, sourceVal.asText().length() - 1);
                  JsonNode resolved = resolvePath(input, expr);
                  childVariables.set(
                      targetKey, resolved != null ? resolved : JsonNodeFactory.instance.nullNode());
                } else {
                  childVariables.set(targetKey, sourceVal.deepCopy());
                }
              });
    } else if (input instanceof ObjectNode objInput && !objInput.isEmpty()) {
      childVariables.setAll(objInput.deepCopy());
    }

    // 5. Create Child Event
    UUID childEventId = uuidGenerator.generate();
    Event childEvent =
        Event.createChild(
            childEventId,
            parentEvent.getTicketId(),
            childVersion.getId(),
            parentEvent.getStartedTicketRevisionId(),
            parentEvent.getRootEventId(),
            parentEvent.getId(),
            parentNodeExecution.getId(),
            "SUB_WORKFLOW",
            parentNodeExecution.getId().toString(),
            childVariables,
            parentEvent.getStartedBy(),
            now);
    childEvent.markRunning();
    childEvent = eventRepository.save(childEvent);

    // 6. Persist SubWorkflowExecution tracking record
    SubWorkflowExecution subExec =
        SubWorkflowExecution.create(
            uuidGenerator.generate(),
            parentEvent.getId(),
            parentNodeExecution.getId(),
            childEvent.getId(),
            childDef.getId(),
            childVersion.getId(),
            executionMode,
            cancellationPolicy,
            childVariables.toString(),
            now);
    subWorkflowExecutionRepository.save(subExec);

    log.info(
        "SubWorkflow child Event created: childEventId={}, parentEventId={}, parentNodeExecutionId={}, childVersionId={}, mode={}",
        childEvent.getId(),
        parentEvent.getId(),
        parentNodeExecution.getId(),
        childVersion.getId(),
        executionMode);

    // 7. Activate child workflow entry node
    activationService.activateRoot(
        childEvent.getId(), uuidGenerator.generate(), request.correlationId(), request.commandId());

    // 8. Return result according to execution mode
    if (executionMode == SubWorkflowExecutionMode.WAIT_FOR_COMPLETION) {
      ObjectNode waitDetails = objectMapper.createObjectNode();
      waitDetails.put("childEventId", childEventId.toString());
      waitDetails.put("childWorkflowVersionId", childVersion.getId().toString());
      return NodeExecutionResult.waitFor(
          new WaitDescriptor("CHILD_EVENT", childEventId.toString(), waitDetails));
    } else {
      ObjectNode completeOutput = objectMapper.createObjectNode();
      completeOutput.put("childEventId", childEventId.toString());
      completeOutput.put("childWorkflowVersionId", childVersion.getId().toString());
      return NodeExecutionResult.complete(completeOutput, "COMPLETED");
    }
  }

  @Override
  @Transactional
  public void onChildEventTerminal(
      Event childEvent, CorrelationId correlationId, CommandId commandId) {
    Optional<SubWorkflowExecution> subOpt =
        subWorkflowExecutionRepository.findByChildEventIdForUpdate(childEvent.getId());
    if (subOpt.isEmpty()) {
      return;
    }
    SubWorkflowExecution subExec = subOpt.get();
    if (subExec.getStatus() != SubWorkflowExecutionStatus.RUNNING) {
      return;
    }

    Instant now = clock.now();
    if (subExec.getExecutionMode() == SubWorkflowExecutionMode.FIRE_AND_CONTINUE) {
      subExec.markCompleted(
          childEvent.getVariablesJson() != null ? childEvent.getVariablesJson().toString() : "{}",
          now);
      subWorkflowExecutionRepository.save(subExec);
      return;
    }

    // WAIT_FOR_COMPLETION
    NodeExecution parentNode =
        nodeExecutionRepository.findByIdForUpdate(subExec.getParentNodeExecutionId()).orElse(null);
    if (parentNode == null) {
      subExec.markCompleted(
          childEvent.getVariablesJson() != null ? childEvent.getVariablesJson().toString() : "{}",
          now);
      subWorkflowExecutionRepository.save(subExec);
      return;
    }

    // Terminal-wins check on parent node: if parent node is already terminal, do not resume
    if (parentNode.getStatus() != NodeExecutionStatus.WAITING) {
      log.info(
          "Parent node execution {} is already terminal ({}); skipping resume for child event {}",
          parentNode.getId(),
          parentNode.getStatus(),
          childEvent.getId());
      subExec.markCompleted(
          childEvent.getVariablesJson() != null ? childEvent.getVariablesJson().toString() : "{}",
          now);
      subWorkflowExecutionRepository.save(subExec);
      return;
    }

    NodeDefinition nodeDef =
        nodeDefinitionRepository.findById(parentNode.getNodeDefinitionId()).orElse(null);
    JsonNode outputMappings =
        nodeDef != null ? nodeDef.getConfigJson().path("outputMappings") : null;

    ObjectNode parentOutput = objectMapper.createObjectNode();
    if (outputMappings != null && outputMappings.isObject()) {
      outputMappings
          .fields()
          .forEachRemaining(
              entry -> {
                String targetKey = entry.getKey();
                JsonNode sourceVal = entry.getValue();
                if (sourceVal.isTextual()
                    && sourceVal.asText().startsWith("${")
                    && sourceVal.asText().endsWith("}")) {
                  String expr = sourceVal.asText().substring(2, sourceVal.asText().length() - 1);
                  JsonNode resolved = resolvePath(childEvent.getVariablesJson(), expr);
                  parentOutput.set(
                      targetKey, resolved != null ? resolved : JsonNodeFactory.instance.nullNode());
                } else {
                  parentOutput.set(targetKey, sourceVal.deepCopy());
                }
              });
    } else if (childEvent.getVariablesJson() instanceof ObjectNode childVars
        && !childVars.isEmpty()) {
      parentOutput.setAll(childVars.deepCopy());
    }

    String outcomePort;
    if (childEvent.getStatus() == EventStatus.COMPLETED) {
      outcomePort = "COMPLETED";
      parentNode.complete(outcomePort, parentOutput, now);
      subExec.markCompleted(parentOutput.toString(), now);
    } else if (childEvent.getStatus() == EventStatus.CANCELLED) {
      outcomePort = "CANCELLED";
      parentOutput.put("outcome", "CANCELLED");
      parentNode.complete(outcomePort, parentOutput, now);
      subExec.markCancelled(now);
    } else {
      outcomePort = "FAILED";
      parentOutput.put("outcome", "FAILED");
      parentNode.complete(outcomePort, parentOutput, now);
      subExec.markFailed(parentOutput.toString(), now);
    }

    nodeExecutionRepository.saveAndFlush(parentNode);
    subWorkflowExecutionRepository.save(subExec);

    log.info(
        "Parent node execution {} completed from child event {} with outcomePort={}",
        parentNode.getId(),
        childEvent.getId(),
        outcomePort);

    // Route parent workflow downstream
    CorrelationId corr =
        correlationId != null ? correlationId : new CorrelationId(uuidGenerator.generate());
    CommandId cmd = commandId != null ? commandId : new CommandId(uuidGenerator.generate());
    routingService.route(parentNode.getId(), corr, cmd);
    eventLifecycleService.syncEventStatus(subExec.getParentEventId());
  }

  @Override
  @Transactional
  public void handleParentCancellation(UUID parentNodeExecutionId, Instant now) {
    Optional<SubWorkflowExecution> subOpt =
        subWorkflowExecutionRepository.findByParentNodeExecutionId(parentNodeExecutionId);
    if (subOpt.isEmpty()) {
      return;
    }
    SubWorkflowExecution subExec = subOpt.get();
    if (subExec.getStatus() != SubWorkflowExecutionStatus.RUNNING) {
      return;
    }

    if (subExec.getCancellationPolicy() == SubWorkflowCancellationPolicy.PROPAGATE) {
      Event childEvent = eventRepository.findById(subExec.getChildEventId()).orElse(null);
      if (childEvent != null
          && !EventLifecycleService.TERMINAL_EVENT_STATUSES.contains(childEvent.getStatus())) {
        CorrelationId corr = new CorrelationId(uuidGenerator.generate());
        CommandId cmd = new CommandId(uuidGenerator.generate());
        eventLifecycleService.cancelEvent(
            childEvent.getId(), cmd, corr, "Propagated cancellation from parent");
      }
      subExec.markCancelled(now);
      subWorkflowExecutionRepository.save(subExec);
    }
  }

  private JsonNode resolvePath(JsonNode source, String path) {
    if (source == null || path == null || path.isBlank()) {
      return null;
    }
    if (path.startsWith("input.")) {
      path = path.substring(6);
    } else if (path.startsWith("variables.")) {
      path = path.substring(10);
    }
    String[] parts = path.split("\\.");
    JsonNode curr = source;
    for (String part : parts) {
      if (curr == null || !curr.isObject()) {
        return null;
      }
      curr = curr.get(part);
    }
    return curr;
  }
}
