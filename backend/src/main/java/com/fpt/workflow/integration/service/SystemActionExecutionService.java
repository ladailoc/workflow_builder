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
    NodeExecution nodeExecution =
        nodeExecutionRepo
            .findById(nodeExecutionId)
            .orElseThrow(
                () -> new IllegalArgumentException("NodeExecution not found: " + nodeExecutionId));
    return execute(nodeExecution.getEventId(), nodeExecutionId, correlationId, commandId);
  }

  public SystemActionResult execute(
      UUID eventId, UUID nodeExecutionId, CorrelationId correlationId, CommandId commandId) {
    NodeExecution nodeExecution =
        nodeExecutionRepo
            .findById(nodeExecutionId)
            .orElseThrow(
                () -> new IllegalArgumentException("NodeExecution not found: " + nodeExecutionId));
    NodeDefinition nodeDefinition =
        nodeDefinitionRepo
            .findById(nodeExecution.getNodeDefinitionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "NodeDefinition not found: " + nodeExecution.getNodeDefinitionId()));

    JsonNode config = nodeDefinition.getConfigJson();
    String connectorKey = config.path("connectorKey").asText();
    String actionKey = config.path("actionKey").asText();
    int actionVersion = config.path("actionVersion").asInt(1);
    String credentialRef = config.path("credentialRef").asText(null);

    ConnectorActionVersion actionVersionDef =
        connectorRegistry.requireActionVersion(connectorKey, actionKey, actionVersion);

    RetryPolicy retryPolicy =
        parseRetryPolicy(
            actionVersionDef.getRetryPolicyJson(), actionVersionDef.getIdempotencyPolicyJson());
    JsonNode executionConfig = parseExecutionConfig(actionVersionDef.getExecutionConfigJson());
    JsonNode inputData = nodeExecution.getInputJson();

    String logicalActionIdentity =
        "event:"
            + eventId
            + ":node:"
            + nodeExecutionId
            + ":action:"
            + connectorKey
            + "/"
            + actionKey
            + ":v"
            + actionVersion;
    // Logical idempotency identity reused across all retries of this node execution
    String idempotencyKey =
        "idemp:" + nodeExecutionId + ":" + connectorKey + ":" + actionKey + ":" + actionVersion;

    boolean isAsyncCallback =
        config.path("asyncCallback").asBoolean(false)
            || "ASYNC_CALLBACK".equalsIgnoreCase(config.path("pattern").asText(""))
            || "ASYNC_CALLBACK".equalsIgnoreCase(executionConfig.path("pattern").asText(""));
    String callbackCorrelationId =
        isAsyncCallback ? callbackCorrelationService.generateCorrelationId() : null;

    // ──────────────────────────────────────────────────────────────────────────
    // TX1: persist running execution + initial attempt -> COMMIT
    // ──────────────────────────────────────────────────────────────────────────
    IntegrationExecution execution =
        txService.startExecutionTx(
            eventId,
            nodeExecutionId,
            connectorKey,
            actionKey,
            actionVersion,
            logicalActionIdentity,
            idempotencyKey,
            inputData);

    // ──────────────────────────────────────────────────────────────────────────
    // External network call (ZERO database locks / outside any transaction)
    // ──────────────────────────────────────────────────────────────────────────
    int attemptNumber = 1;
    IntegrationCallResponse lastResponse = null;

    while (true) {
      if (attemptNumber > 1) {
        // Start next attempt in its own independent transaction
        txService.startAttemptTx(execution.getId(), attemptNumber, inputData);
      }

      IntegrationCallRequest callRequest =
          new IntegrationCallRequest(
              execution.getId(),
              connectorKey,
              actionKey,
              actionVersion,
              idempotencyKey,
              inputData,
              credentialRef,
              executionConfig);

      try {
        lastResponse = actionClient.execute(callRequest);
      } catch (Exception ex) {
        lastResponse = mapExceptionToResponse(ex);
      }

      // Record attempt result in its own independent transaction
      txService.recordAttemptResultTx(execution.getId(), attemptNumber, lastResponse);

      if (lastResponse.success()) {
        break;
      }

      // Evaluate retry policy: non-idempotent actions have no blind automatic retry
      if (!retryPolicy.canRetry(lastResponse.errorCategory(), attemptNumber)) {
        break;
      }

      if (retryPolicy.backoffMs() > 0) {
        try {
          Thread.sleep(retryPolicy.backoffMs());
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          break;
        }
      }

      attemptNumber++;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TX2: persist result/outcome + continuation intent -> COMMIT
    // ──────────────────────────────────────────────────────────────────────────
    if (isAsyncCallback && lastResponse != null && lastResponse.success()) {
      return txService.transitionToWaitingCallbackTx(
          execution.getId(), callbackCorrelationId, lastResponse);
    }
    return txService.completeExecutionTx(execution.getId(), lastResponse, correlationId, commandId);
  }

  private RetryPolicy parseRetryPolicy(JsonNode retryNode, JsonNode idempotencyNode) {
    boolean idempotent = true;
    if (idempotencyNode != null && idempotencyNode.has("idempotent")) {
      idempotent = idempotencyNode.get("idempotent").asBoolean(true);
    } else if (retryNode != null && retryNode.has("idempotent")) {
      idempotent = retryNode.get("idempotent").asBoolean(true);
    }
    RetryPolicy base = RetryPolicy.fromJson(retryNode);
    if (!idempotent) {
      return new RetryPolicy(false, 0, 0, java.util.Set.of());
    }
    return base;
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
