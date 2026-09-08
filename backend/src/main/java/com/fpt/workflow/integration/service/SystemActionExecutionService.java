package com.fpt.workflow.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.connector.domain.ConnectorActionVersion;
import com.fpt.workflow.connector.service.ConnectorRegistry;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.integration.client.ConnectorActionClient;
import com.fpt.workflow.integration.client.IntegrationCallRequest;
import com.fpt.workflow.integration.client.IntegrationCallResponse;
import com.fpt.workflow.integration.domain.IntegrationErrorCategory;
import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.domain.RetryPolicy;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * Executes SystemAction nodes adhering strictly to the two-phase transaction rule: TX1: persist
 * running execution + initial attempt -> COMMIT External network call outside any database
 * transaction (zero locks held) TX2: persist result/outcome + continuation intent (routing
 * downstream) -> COMMIT
 */
@Service
public class SystemActionExecutionService {

  private final SystemActionTransactionService txService;
  private final ConnectorRegistry connectorRegistry;
  private final ConnectorActionClient actionClient;
  private final NodeExecutionRepository nodeExecutionRepo;
  private final NodeDefinitionRepository nodeDefinitionRepo;
  private final ObjectMapper objectMapper;
  private final CallbackCorrelationService callbackCorrelationService;

  public SystemActionExecutionService(
      SystemActionTransactionService txService,
      ConnectorRegistry connectorRegistry,
      ConnectorActionClient actionClient,
      NodeExecutionRepository nodeExecutionRepo,
      NodeDefinitionRepository nodeDefinitionRepo,
      ObjectMapper objectMapper,
      CallbackCorrelationService callbackCorrelationService) {
    this.txService = Objects.requireNonNull(txService, "txService");
    this.connectorRegistry = Objects.requireNonNull(connectorRegistry, "connectorRegistry");
    this.actionClient = Objects.requireNonNull(actionClient, "actionClient");
    this.nodeExecutionRepo = Objects.requireNonNull(nodeExecutionRepo, "nodeExecutionRepo");
    this.nodeDefinitionRepo = Objects.requireNonNull(nodeDefinitionRepo, "nodeDefinitionRepo");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.callbackCorrelationService =
        Objects.requireNonNull(callbackCorrelationService, "callbackCorrelationService");
  }

  public SystemActionResult execute(
      UUID nodeExecutionId, CorrelationId correlationId, CommandId commandId) {
    int attemptNumber = 1;
    while (true) {
      SystemActionAttemptOutcome outcome =
          executeDurableAttempt(nodeExecutionId, attemptNumber, correlationId, commandId);
      if (outcome.terminal()) return outcome.terminalResult();
      attemptNumber++;
    }
  }

  /** Executes exactly one externally-visible attempt for a leased durable job. */
  public SystemActionAttemptOutcome executeDurableAttempt(
      UUID nodeExecutionId, int attemptNumber, CorrelationId correlationId, CommandId commandId) {
    if (attemptNumber <= 0) throw new IllegalArgumentException("attemptNumber must be positive");
    NodeExecution nodeExecution =
        nodeExecutionRepo
            .findById(nodeExecutionId)
            .orElseThrow(
                () -> new IllegalArgumentException("NodeExecution not found: " + nodeExecutionId));
    NodeDefinition nodeDefinition =
        nodeDefinitionRepo.findById(nodeExecution.getNodeDefinitionId()).orElseThrow();
    JsonNode config = nodeDefinition.getConfigJson();
    String connectorKey = config.path("connectorKey").asText();
    String actionKey = config.path("actionKey").asText();
    int actionVersion = config.path("actionVersion").asInt(1);
    String credentialRef = config.path("credentialRef").asText(null);
    ConnectorActionVersion action =
        connectorRegistry.requireActionVersion(connectorKey, actionKey, actionVersion);
    RetryPolicy retryPolicy =
        parseRetryPolicy(action.getRetryPolicyJson(), action.getIdempotencyPolicyJson());
    JsonNode executionConfig = parseExecutionConfig(action.getExecutionConfigJson());
    JsonNode inputData = nodeExecution.getInputJson();
    String idempotencyKey =
        "idemp:" + nodeExecutionId + ":" + connectorKey + ":" + actionKey + ":" + actionVersion;
    String logicalIdentity =
        "event:"
            + nodeExecution.getEventId()
            + ":node:"
            + nodeExecutionId
            + ":action:"
            + connectorKey
            + "/"
            + actionKey
            + ":v"
            + actionVersion;
    IntegrationExecution execution =
        txService.startOrResumeExecutionTx(
            nodeExecution.getEventId(),
            nodeExecutionId,
            connectorKey,
            actionKey,
            actionVersion,
            logicalIdentity,
            idempotencyKey,
            inputData,
            attemptNumber);
    if (execution.getStatus()
        != com.fpt.workflow.integration.domain.IntegrationExecutionStatus.RUNNING) {
      return SystemActionAttemptOutcome.idempotentReplay();
    }
    IntegrationCallRequest request =
        new IntegrationCallRequest(
            execution.getId(),
            connectorKey,
            actionKey,
            actionVersion,
            idempotencyKey,
            inputData,
            credentialRef,
            executionConfig);
    IntegrationCallResponse response;
    try {
      response = actionClient.execute(request);
    } catch (Exception ex) {
      response = mapExceptionToResponse(ex);
    }
    txService.recordAttemptResultTx(execution.getId(), attemptNumber, response);
    if (!response.success()
        && !isIdempotent(action.getRetryPolicyJson(), action.getIdempotencyPolicyJson())
        && isUncertain(response.errorCategory())) {
      return SystemActionAttemptOutcome.terminal(
          txService.transitionToManualReconciliationTx(execution.getId(), response));
    }
    boolean async =
        config.path("asyncCallback").asBoolean(false)
            || "ASYNC_CALLBACK".equalsIgnoreCase(config.path("pattern").asText(""))
            || "ASYNC_CALLBACK".equalsIgnoreCase(executionConfig.path("pattern").asText(""));
    if (async && response.success()) {
      return SystemActionAttemptOutcome.terminal(
          txService.transitionToWaitingCallbackTx(
              execution.getId(), callbackCorrelationService.generateCorrelationId(), response));
    }
    if (!response.success() && retryPolicy.canRetry(response.errorCategory(), attemptNumber)) {
      var error = objectMapper.createObjectNode();
      error.put("category", response.errorCategory().name());
      error.put("message", response.errorMessage());
      return SystemActionAttemptOutcome.retry(
          java.time.Duration.ofMillis(retryPolicy.backoffMs()), error);
    }
    return SystemActionAttemptOutcome.terminal(
        txService.completeExecutionTx(execution.getId(), response, correlationId, commandId));
  }

  public SystemActionResult execute(
      UUID eventId, UUID nodeExecutionId, CorrelationId correlationId, CommandId commandId) {
    NodeExecution nodeExecution =
        nodeExecutionRepo
            .findById(nodeExecutionId)
            .orElseThrow(
                () -> new IllegalArgumentException("NodeExecution not found: " + nodeExecutionId));
    if (!nodeExecution.getEventId().equals(eventId)) {
      throw new IllegalArgumentException("NodeExecution does not belong to Event: " + eventId);
    }
    return execute(nodeExecutionId, correlationId, commandId);
  }

  private RetryPolicy parseRetryPolicy(JsonNode retryNode, JsonNode idempotencyNode) {
    boolean idempotent = isIdempotent(retryNode, idempotencyNode);
    RetryPolicy base = RetryPolicy.fromJson(retryNode);
    if (!idempotent) {
      return new RetryPolicy(false, 0, 0, java.util.Set.of());
    }
    return base;
  }

  private boolean isIdempotent(JsonNode retryNode, JsonNode idempotencyNode) {
    if (idempotencyNode != null && idempotencyNode.has("idempotent")) {
      return idempotencyNode.get("idempotent").asBoolean(true);
    }
    return retryNode == null
        || !retryNode.has("idempotent")
        || retryNode.get("idempotent").asBoolean(true);
  }

  private boolean isUncertain(IntegrationErrorCategory category) {
    return category == IntegrationErrorCategory.LOST_RESPONSE
        || category == IntegrationErrorCategory.TIMEOUT
        || category == IntegrationErrorCategory.NETWORK_ERROR;
  }

  private JsonNode parseExecutionConfig(JsonNode node) {
    if (node == null || node.isNull()) {
      return objectMapper.createObjectNode();
    }
    return node;
  }

  private IntegrationCallResponse mapExceptionToResponse(Exception ex) {
    Throwable cause = ex;
    while (cause != null) {
      if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
        return IntegrationCallResponse.timeout(
            cause.getMessage() != null ? cause.getMessage() : "Timeout");
      }
      if (cause instanceof java.io.IOException) {
        return IntegrationCallResponse.networkError(
            cause.getMessage() != null ? cause.getMessage() : "Network error");
      }
      cause = cause.getCause();
    }
    return IntegrationCallResponse.failure(
        IntegrationErrorCategory.CLIENT_ERROR,
        0,
        ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(),
        null);
  }
}
