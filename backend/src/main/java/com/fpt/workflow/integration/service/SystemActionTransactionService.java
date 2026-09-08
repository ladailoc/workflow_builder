package com.fpt.workflow.integration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.integration.client.IntegrationCallResponse;
import com.fpt.workflow.integration.domain.IntegrationAttempt;
import com.fpt.workflow.integration.domain.IntegrationErrorCategory;
import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.repository.IntegrationAttemptRepository;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.binding.EventVariableMapper;
import com.fpt.workflow.runtime.binding.VariableMapping;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.context.EventContextBuilder;
import com.fpt.workflow.runtime.context.RuntimeScope;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemActionTransactionService {

  private static final TypeReference<List<VariableMapping>> VARIABLE_MAPPINGS =
      new TypeReference<>() {};

  private final IntegrationExecutionRepository executionRepo;
  private final IntegrationAttemptRepository attemptRepo;
  private final AuditEventRepository auditRepo;
  private final NodeExecutionRepository nodeExecutionRepo;
  private final NodeDefinitionRepository nodeDefinitionRepo;
  private final EventRepository eventRepo;
  private final WorkflowVariableRepository variableRepo;
  private final EventVariableMapper variableMapper;
  private final EventContextBuilder contextBuilder;
  private final RoutingService routingService;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;

  public SystemActionTransactionService(
      IntegrationExecutionRepository executionRepo,
      IntegrationAttemptRepository attemptRepo,
      AuditEventRepository auditRepo,
      NodeExecutionRepository nodeExecutionRepo,
      NodeDefinitionRepository nodeDefinitionRepo,
      EventRepository eventRepo,
      WorkflowVariableRepository variableRepo,
      EventVariableMapper variableMapper,
      EventContextBuilder contextBuilder,
      @Lazy RoutingService routingService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper) {
    this.executionRepo = Objects.requireNonNull(executionRepo, "executionRepo");
    this.attemptRepo = Objects.requireNonNull(attemptRepo, "attemptRepo");
    this.auditRepo = Objects.requireNonNull(auditRepo, "auditRepo");
    this.nodeExecutionRepo = Objects.requireNonNull(nodeExecutionRepo, "nodeExecutionRepo");
    this.nodeDefinitionRepo = Objects.requireNonNull(nodeDefinitionRepo, "nodeDefinitionRepo");
    this.eventRepo = Objects.requireNonNull(eventRepo, "eventRepo");
    this.variableRepo = Objects.requireNonNull(variableRepo, "variableRepo");
    this.variableMapper = Objects.requireNonNull(variableMapper, "variableMapper");
    this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
    this.routingService = Objects.requireNonNull(routingService, "routingService");
    this.uuidGenerator = Objects.requireNonNull(uuidGenerator, "uuidGenerator");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /**
   * TX1: Persist running execution + initial attempt. Commits before external network call begins.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IntegrationExecution startExecutionTx(
      UUID eventId,
      UUID nodeExecutionId,
      String connectorKey,
      String actionKey,
      int actionVersion,
      UUID connectorActionVersionId,
      String logicalActionIdentity,
      String idempotencyKey,
      JsonNode rawRequest,
      CorrelationId correlationId,
      CommandId commandId) {
    Instant now = clock.now();
    JsonNode sanitizedRequest = PayloadSanitizer.sanitize(rawRequest);
    String requestJson = sanitizedRequest != null ? sanitizedRequest.toString() : null;

    IntegrationExecution execution =
        IntegrationExecution.createRunning(
            uuidGenerator.generate(),
            eventId,
            nodeExecutionId,
            connectorKey,
            actionKey,
            actionVersion,
            connectorActionVersionId,
            logicalActionIdentity,
            idempotencyKey,
            requestJson,
            now);
    execution = executionRepo.saveAndFlush(execution);

    IntegrationAttempt attempt =
        IntegrationAttempt.createRunning(
            uuidGenerator.generate(), execution.getId(), 1, requestJson, now);
    attemptRepo.saveAndFlush(attempt);
    audit(execution, "ACTION_STARTED", correlationId, commandId, now);

    return execution;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IntegrationExecution startOrResumeExecutionTx(
      UUID eventId,
      UUID nodeExecutionId,
      String connectorKey,
      String actionKey,
      int actionVersion,
      UUID connectorActionVersionId,
      String logicalActionIdentity,
      String idempotencyKey,
      JsonNode rawRequest,
      int attemptNumber,
      CorrelationId correlationId,
      CommandId commandId) {
    var existing = executionRepo.findByNodeExecutionId(nodeExecutionId);
    if (existing.isEmpty()) {
      if (attemptNumber != 1) {
        throw new IllegalStateException("First durable integration attempt must be number 1");
      }
      return startExecutionTx(
          eventId,
          nodeExecutionId,
          connectorKey,
          actionKey,
          actionVersion,
          connectorActionVersionId,
          logicalActionIdentity,
          idempotencyKey,
          rawRequest,
          correlationId,
          commandId);
    }
    IntegrationExecution execution = existing.orElseThrow();
    if (!execution.getConnectorActionVersionId().equals(connectorActionVersionId)
        || !execution.getConnectorKey().equals(connectorKey)
        || !execution.getActionKey().equals(actionKey)
        || execution.getActionVersion() != actionVersion) {
      throw new IllegalStateException(
          "A durable integration execution cannot be rebound to a different connector action version");
    }
    if (execution.getStatus()
        != com.fpt.workflow.integration.domain.IntegrationExecutionStatus.RUNNING) {
      return execution;
    }
    if (attemptRepo
        .findByIntegrationExecutionIdAndAttemptNumber(execution.getId(), attemptNumber)
        .isEmpty()) {
      startAttemptTx(execution.getId(), attemptNumber, rawRequest);
      audit(execution, "ACTION_RETRIED", correlationId, commandId, clock.now());
    }
    return execution;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void startAttemptTx(UUID executionId, int attemptNumber, JsonNode rawRequest) {
    Instant now = clock.now();
    JsonNode sanitizedRequest = PayloadSanitizer.sanitize(rawRequest);
    String requestJson = sanitizedRequest != null ? sanitizedRequest.toString() : null;

    IntegrationAttempt attempt =
        IntegrationAttempt.createRunning(
            uuidGenerator.generate(), executionId, attemptNumber, requestJson, now);
    attemptRepo.saveAndFlush(attempt);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordAttemptResultTx(
      UUID executionId, int attemptNumber, IntegrationCallResponse response) {
    Instant now = clock.now();
    IntegrationAttempt attempt =
        attemptRepo
            .findByIntegrationExecutionIdAndAttemptNumber(executionId, attemptNumber)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "IntegrationAttempt not found: " + executionId + " #" + attemptNumber));

    JsonNode sanitizedPayload = PayloadSanitizer.sanitize(response.payload());
    String responseJson = sanitizedPayload != null ? sanitizedPayload.toString() : null;

    if (response.success()) {
      attempt.markSuccess(responseJson, now);
    } else {
      attempt.markFailure(
          response.errorCategory() != null
              ? response.errorCategory()
              : IntegrationErrorCategory.CLIENT_ERROR,
          response.errorMessage(),
          responseJson,
          now);
    }
    attemptRepo.saveAndFlush(attempt);
  }

  /**
   * TX2: Persist result/outcome and continuation intent (downstream routing). Commits after
   * external network call and attempt handling finish.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SystemActionResult completeExecutionTx(
      UUID executionId,
      IntegrationCallResponse finalResponse,
      CorrelationId correlationId,
      CommandId commandId) {
    Instant now = clock.now();
    IntegrationExecution execution =
        executionRepo
            .findById(executionId)
            .orElseThrow(
                () -> new IllegalStateException("IntegrationExecution not found: " + executionId));

    JsonNode sanitizedResponse = PayloadSanitizer.sanitize(finalResponse.payload());
    String responseJson = sanitizedResponse != null ? sanitizedResponse.toString() : null;

    if (finalResponse.success()) {
      execution.markCompleted(responseJson, now);
    } else {
      execution.markFailed(finalResponse.errorCategory(), responseJson, now);
    }
    IntegrationExecution savedExecution = executionRepo.saveAndFlush(execution);
    audit(
        savedExecution,
        finalResponse.success() ? "ACTION_SUCCEEDED" : "ACTION_FAILED",
        correlationId,
        commandId,
        now);
    final UUID targetNodeExecutionId = savedExecution.getNodeExecutionId();
    final UUID targetEventId = savedExecution.getEventId();

    // Update NodeExecution
    NodeExecution nodeExecution =
        nodeExecutionRepo
            .findByIdForUpdate(targetNodeExecutionId)
            .orElseThrow(
                () ->
                    new IllegalStateException("NodeExecution not found: " + targetNodeExecutionId));

    Event event =
        eventRepo
            .findById(targetEventId)
            .orElseThrow(() -> new IllegalStateException("Event not found: " + targetEventId));

    final UUID targetNodeDefinitionId = nodeExecution.getNodeDefinitionId();
    NodeDefinition node =
        nodeDefinitionRepo
            .findById(targetNodeDefinitionId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "NodeDefinition not found: " + targetNodeDefinitionId));

    String outcomePort;
    ObjectNode outputObject = JsonNodeFactory.instance.objectNode();
    if (finalResponse.success()) {
      outcomePort = "SUCCESS";
      if (sanitizedResponse instanceof ObjectNode obj) {
        outputObject.setAll(obj);
      } else if (sanitizedResponse != null) {
        outputObject.set("result", sanitizedResponse);
      }
    } else {
      outcomePort = "ERROR";
      outputObject.put(
          "errorCategory",
          finalResponse.errorCategory() != null
              ? finalResponse.errorCategory().name()
              : "CLIENT_ERROR");
      outputObject.put("statusCode", finalResponse.statusCode());
      outputObject.put(
          "errorMessage",
          finalResponse.errorMessage() != null
              ? finalResponse.errorMessage()
              : "Action execution failed");
      if (sanitizedResponse != null) {
        outputObject.set("details", sanitizedResponse);
      }
    }

    nodeExecution.complete(outcomePort, outputObject, now);
    nodeExecution = nodeExecutionRepo.saveAndFlush(nodeExecution);

    // Apply variable mappings if configured
    List<VariableMapping> mappings = decodeMappings(node.getConfigJson());
    if (!mappings.isEmpty()) {
      RuntimeScope scope =
          RuntimeScope.occurrence(
              nodeExecution.getCycleId(),
              nodeExecution.getPathToken(),
              nodeExecution.getItemToken());
      EventContext afterOutput = contextBuilder.build(event.getId(), scope);
      List<WorkflowVariable> declarations =
          variableRepo.findAllByWorkflowVersionIdOrderByKeyAsc(event.getWorkflowVersionId());
      variableMapper.apply(event, declarations, mappings, afterOutput);
      eventRepo.saveAndFlush(event);
    }

    // Persist continuation intent: route downstream via RoutingService
    RoutingResult routingResult =
        routingService.route(nodeExecution.getId(), correlationId, commandId);

    return new SystemActionResult(execution, nodeExecution, outcomePort, routingResult);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SystemActionResult transitionToWaitingCallbackTx(
      UUID executionId, String callbackCorrelationId, IntegrationCallResponse initialResponse) {
    Instant now = clock.now();
    IntegrationExecution execution =
        executionRepo
            .findById(executionId)
            .orElseThrow(
                () -> new IllegalStateException("IntegrationExecution not found: " + executionId));

    JsonNode sanitizedResponse = PayloadSanitizer.sanitize(initialResponse.payload());
    String responseJson = sanitizedResponse != null ? sanitizedResponse.toString() : null;

    execution.markWaitingCallback(callbackCorrelationId, now);
    IntegrationExecution savedExecution = executionRepo.saveAndFlush(execution);
    final UUID targetNodeExecutionId = savedExecution.getNodeExecutionId();

    NodeExecution nodeExecution =
        nodeExecutionRepo
            .findByIdForUpdate(targetNodeExecutionId)
            .orElseThrow(
                () ->
                    new IllegalStateException("NodeExecution not found: " + targetNodeExecutionId));

    nodeExecution.waitFor(RuntimeWaitReason.EXTERNAL_CALLBACK);
    NodeExecution savedNodeExecution = nodeExecutionRepo.saveAndFlush(nodeExecution);

    return new SystemActionResult(savedExecution, savedNodeExecution, "WAITING_CALLBACK", null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SystemActionResult transitionToManualReconciliationTx(
      UUID executionId,
      IntegrationCallResponse uncertainResponse,
      CorrelationId correlationId,
      CommandId commandId) {
    Instant now = clock.now();
    IntegrationExecution execution =
        executionRepo
            .findById(executionId)
            .orElseThrow(
                () -> new IllegalStateException("IntegrationExecution not found: " + executionId));
    JsonNode sanitizedResponse = PayloadSanitizer.sanitize(uncertainResponse.payload());
    execution.markManualReconciliation(
        uncertainResponse.errorCategory(),
        sanitizedResponse == null ? null : sanitizedResponse.toString(),
        now);
    execution = executionRepo.saveAndFlush(execution);
    audit(execution, "ACTION_FAILED", correlationId, commandId, now);
    UUID nodeExecutionId = execution.getNodeExecutionId();
    UUID eventId = execution.getEventId();

    NodeExecution nodeExecution =
        nodeExecutionRepo
            .findByIdForUpdate(nodeExecutionId)
            .orElseThrow(
                () -> new IllegalStateException("NodeExecution not found: " + nodeExecutionId));
    nodeExecution.changeWaitReason(RuntimeWaitReason.MANUAL_RECONCILIATION);
    nodeExecution = nodeExecutionRepo.saveAndFlush(nodeExecution);

    Event event =
        eventRepo
            .findByIdForUpdate(eventId)
            .orElseThrow(() -> new IllegalStateException("Event not found: " + eventId));
    event.changeWaitReason(RuntimeWaitReason.MANUAL_RECONCILIATION);
    eventRepo.saveAndFlush(event);
    return new SystemActionResult(execution, nodeExecution, "MANUAL_RECONCILIATION", null);
  }

  private void audit(
      IntegrationExecution execution,
      String eventType,
      CorrelationId correlationId,
      CommandId commandId,
      Instant occurredAt) {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("eventId", execution.getEventId().toString());
    metadata.put("nodeExecutionId", execution.getNodeExecutionId().toString());
    metadata.put("connectorActionVersionId", execution.getConnectorActionVersionId().toString());
    metadata.put("status", execution.getStatus().name());
    auditRepo.save(
        AuditEvent.record(
            uuidGenerator.generate(),
            "INTEGRATION_EXECUTION",
            execution.getId(),
            eventType,
            null,
            null,
            correlationId,
            commandId,
            metadata,
            occurredAt));
  }

  private List<VariableMapping> decodeMappings(JsonNode config) {
    if (config == null) return List.of();
    JsonNode value = config.get("variableMappings");
    return value == null
        ? List.of()
        : List.copyOf(objectMapper.convertValue(value, VARIABLE_MAPPINGS));
  }
}
