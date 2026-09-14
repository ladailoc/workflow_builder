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
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.domain.IntegrationFailureStrategy;
import com.fpt.workflow.integration.domain.RetryPolicy;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
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
  private final IntegrationExecutionRepository integrationExecutionRepo;
  private final ObjectMapper objectMapper;
  private final CallbackCorrelationService callbackCorrelationService;

  public SystemActionExecutionService(
      SystemActionTransactionService txService,
      ConnectorRegistry connectorRegistry,
      ConnectorActionClient actionClient,
      NodeExecutionRepository nodeExecutionRepo,
      NodeDefinitionRepository nodeDefinitionRepo,
      IntegrationExecutionRepository integrationExecutionRepo,
      ObjectMapper objectMapper,
      CallbackCorrelationService callbackCorrelationService) {
    this.txService = Objects.requireNonNull(txService, "txService");
    this.connectorRegistry = Objects.requireNonNull(connectorRegistry, "connectorRegistry");
    this.actionClient = Objects.requireNonNull(actionClient, "actionClient");
    this.nodeExecutionRepo = Objects.requireNonNull(nodeExecutionRepo, "nodeExecutionRepo");
    this.nodeDefinitionRepo = Objects.requireNonNull(nodeDefinitionRepo, "nodeDefinitionRepo");
    this.integrationExecutionRepo =
        Objects.requireNonNull(integrationExecutionRepo, "integrationExecutionRepo");
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
    boolean idempotent =
        isIdempotent(action.getRetryPolicyJson(), action.getIdempotencyPolicyJson());
    if (attemptNumber > 1 && !idempotent) {
      var existing = integrationExecutionRepo.findByNodeExecutionId(nodeExecutionId);
      if (existing.isPresent()
          && existing.orElseThrow().getStatus() == IntegrationExecutionStatus.RUNNING) {
        IntegrationCallResponse uncertain =
            IntegrationCallResponse.lostResponse(
                "A non-idempotent action was reclaimed after an incomplete attempt; manual reconciliation is required");
        return SystemActionAttemptOutcome.terminal(
            txService.transitionToManualReconciliationTx(
                existing.orElseThrow().getId(), uncertain, correlationId, commandId));
      }
    }
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
            action.getId(),
            logicalIdentity,
            idempotencyKey,
            inputData,
            attemptNumber,
            correlationId,
            commandId);
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
    if (!response.success() && !idempotent && isUncertain(response.errorCategory())) {
      return SystemActionAttemptOutcome.terminal(
          txService.transitionToManualReconciliationTx(
              execution.getId(), response, correlationId, commandId));
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
    if (!response.success()) {
      IntegrationFailureStrategy failureStrategy = parseFailureStrategy(config, executionConfig);
      if (failureStrategy != null) {
        switch (failureStrategy) {
          case FALLBACK_ACTION -> {
            String fallbackConnector = parseFallbackConnectorKey(config, executionConfig);
            String fallbackActionKey = parseFallbackActionKey(config, executionConfig);
            int fallbackVersion = parseFallbackActionVersion(config, executionConfig);
            if (fallbackConnector != null && fallbackActionKey != null) {
              ConnectorActionVersion fallbackAction =
                  connectorRegistry.requireActionVersion(
                      fallbackConnector, fallbackActionKey, fallbackVersion);
              JsonNode fallbackExecConfig =
                  parseExecutionConfig(fallbackAction.getExecutionConfigJson());
              String fallbackIdempKey = idempotencyKey + ":fallback";
              IntegrationCallRequest fallbackRequest =
                  new IntegrationCallRequest(
                      execution.getId(),
                      fallbackConnector,
                      fallbackActionKey,
                      fallbackVersion,
                      fallbackIdempKey,
                      inputData,
                      credentialRef,
                      fallbackExecConfig);
              txService.startAttemptTx(execution.getId(), attemptNumber + 1, inputData);
              IntegrationCallResponse fallbackResponse;
              try {
                fallbackResponse = actionClient.execute(fallbackRequest);
              } catch (Exception ex) {
                fallbackResponse = mapExceptionToResponse(ex);
              }
              txService.recordAttemptResultTx(
                  execution.getId(), attemptNumber + 1, fallbackResponse);
              if (fallbackResponse.success()) {
                return SystemActionAttemptOutcome.terminal(
                    txService.completeExecutionTx(
                        execution.getId(), fallbackResponse, correlationId, commandId));
              }
            }
          }
          case CREATE_MANUAL_TASK -> {
            return SystemActionAttemptOutcome.terminal(
                txService.transitionToManualReconciliationTx(
                    execution.getId(), response, correlationId, commandId));
          }
          case FAIL_NODE -> {
            return SystemActionAttemptOutcome.terminal(
                txService.failNodeOnIntegrationFailureTx(
                    execution.getId(), response, correlationId, commandId));
          }
          case FAIL_EVENT -> {
            return SystemActionAttemptOutcome.terminal(
                txService.failEventOnIntegrationFailureTx(
                    execution.getId(), response, correlationId, commandId));
          }
          case CONTINUE_WITH_WARNING -> {
            return SystemActionAttemptOutcome.terminal(
                txService.continueWithWarningTx(
                    execution.getId(), response, correlationId, commandId));
          }
          case GOTO_NODE -> {
            String targetNodeKey = parseTargetNodeKey(config, executionConfig);
            UUID targetNodeId = parseTargetNodeId(config, executionConfig);
            return SystemActionAttemptOutcome.terminal(
                txService.gotoNodeOnIntegrationFailureTx(
                    execution.getId(),
                    targetNodeKey,
                    targetNodeId,
                    response,
                    correlationId,
                    commandId));
          }
        }
      }
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

  private IntegrationFailureStrategy parseFailureStrategy(JsonNode config, JsonNode executionConfig) {
    if (config != null) {
      if (config.hasNonNull("failureStrategy")) {
        IntegrationFailureStrategy s =
            IntegrationFailureStrategy.fromString(config.path("failureStrategy").asText());
        if (s != null) return s;
      }
      if (config.hasNonNull("failurePolicy")) {
        JsonNode fp = config.path("failurePolicy");
        if (fp.hasNonNull("strategy")) {
          IntegrationFailureStrategy s =
              IntegrationFailureStrategy.fromString(fp.path("strategy").asText());
          if (s != null) return s;
        }
      }
    }
    if (executionConfig != null) {
      if (executionConfig.hasNonNull("failureStrategy")) {
        IntegrationFailureStrategy s =
            IntegrationFailureStrategy.fromString(executionConfig.path("failureStrategy").asText());
        if (s != null) return s;
      }
      if (executionConfig.hasNonNull("failurePolicy")) {
        JsonNode fp = executionConfig.path("failurePolicy");
        if (fp.hasNonNull("strategy")) {
          IntegrationFailureStrategy s =
              IntegrationFailureStrategy.fromString(fp.path("strategy").asText());
          if (s != null) return s;
        }
      }
    }
    return null;
  }

  private String parseTargetNodeKey(JsonNode config, JsonNode executionConfig) {
    if (config != null) {
      if (config.hasNonNull("targetNodeKey")) return config.path("targetNodeKey").asText();
      if (config.path("failurePolicy").hasNonNull("targetNodeKey")) {
        return config.path("failurePolicy").path("targetNodeKey").asText();
      }
    }
    if (executionConfig != null) {
      if (executionConfig.hasNonNull("targetNodeKey")) return executionConfig.path("targetNodeKey").asText();
      if (executionConfig.path("failurePolicy").hasNonNull("targetNodeKey")) {
        return executionConfig.path("failurePolicy").path("targetNodeKey").asText();
      }
    }
    return null;
  }

  private UUID parseTargetNodeId(JsonNode config, JsonNode executionConfig) {
    String idStr = null;
    if (config != null) {
      if (config.hasNonNull("targetNodeId")) idStr = config.path("targetNodeId").asText();
      else if (config.path("failurePolicy").hasNonNull("targetNodeId")) {
        idStr = config.path("failurePolicy").path("targetNodeId").asText();
      }
    }
    if (idStr == null && executionConfig != null) {
      if (executionConfig.hasNonNull("targetNodeId")) idStr = executionConfig.path("targetNodeId").asText();
      else if (executionConfig.path("failurePolicy").hasNonNull("targetNodeId")) {
        idStr = executionConfig.path("failurePolicy").path("targetNodeId").asText();
      }
    }
    if (idStr != null && !idStr.isBlank()) {
      try {
        return UUID.fromString(idStr.trim());
      } catch (IllegalArgumentException ignored) {
      }
    }
    return null;
  }

  private String parseFallbackConnectorKey(JsonNode config, JsonNode executionConfig) {
    if (config != null) {
      if (config.hasNonNull("fallbackConnectorKey")) return config.path("fallbackConnectorKey").asText();
      if (config.path("failurePolicy").hasNonNull("fallbackConnectorKey")) {
        return config.path("failurePolicy").path("fallbackConnectorKey").asText();
      }
    }
    if (executionConfig != null) {
      if (executionConfig.hasNonNull("fallbackConnectorKey")) return executionConfig.path("fallbackConnectorKey").asText();
      if (executionConfig.path("failurePolicy").hasNonNull("fallbackConnectorKey")) {
        return executionConfig.path("failurePolicy").path("fallbackConnectorKey").asText();
      }
    }
    return null;
  }

  private String parseFallbackActionKey(JsonNode config, JsonNode executionConfig) {
    if (config != null) {
      if (config.hasNonNull("fallbackActionKey")) return config.path("fallbackActionKey").asText();
      if (config.path("failurePolicy").hasNonNull("fallbackActionKey")) {
        return config.path("failurePolicy").path("fallbackActionKey").asText();
      }
    }
    if (executionConfig != null) {
      if (executionConfig.hasNonNull("fallbackActionKey")) return executionConfig.path("fallbackActionKey").asText();
      if (executionConfig.path("failurePolicy").hasNonNull("fallbackActionKey")) {
        return executionConfig.path("failurePolicy").path("fallbackActionKey").asText();
      }
    }
    return null;
  }

  private int parseFallbackActionVersion(JsonNode config, JsonNode executionConfig) {
    if (config != null) {
      if (config.hasNonNull("fallbackActionVersion")) return config.path("fallbackActionVersion").asInt(1);
      if (config.path("failurePolicy").hasNonNull("fallbackActionVersion")) {
        return config.path("failurePolicy").path("fallbackActionVersion").asInt(1);
      }
    }
    if (executionConfig != null) {
      if (executionConfig.hasNonNull("fallbackActionVersion")) return executionConfig.path("fallbackActionVersion").asInt(1);
      if (executionConfig.path("failurePolicy").hasNonNull("fallbackActionVersion")) {
        return executionConfig.path("failurePolicy").path("fallbackActionVersion").asInt(1);
      }
    }
    return 1;
  }
}
