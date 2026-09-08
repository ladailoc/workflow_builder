package com.fpt.workflow.integration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.connector.domain.ConnectorDefinition;
import com.fpt.workflow.connector.repository.ConnectorDefinitionRepository;
import com.fpt.workflow.integration.domain.IntegrationCallback;
import com.fpt.workflow.integration.domain.IntegrationCallbackStatus;
import com.fpt.workflow.integration.domain.IntegrationErrorCategory;
import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationCallbackRepository;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultCallbackCorrelationService implements CallbackCorrelationService {

  private static final Logger log =
      LoggerFactory.getLogger(DefaultCallbackCorrelationService.class);
  private static final long MAX_DRIFT_SECONDS = 300; // 5 minutes

  private final PlatformClock clock;
  private final UuidGenerator uuidGenerator;
  private final ObjectMapper objectMapper;
  private final ConnectorDefinitionRepository connectorRepo;
  private final ConnectorCredentialProvider credentialProvider;
  private final CallbackSignatureValidator signatureValidator;
  private final IntegrationCallbackRepository callbackRepo;
  private final IntegrationExecutionRepository executionRepo;
  private final AuditEventRepository auditRepo;
  private final NodeExecutionRepository nodeExecutionRepo;
  private final EventRepository eventRepo;
  private final RoutingService routingService;
  private final SecureRandom secureRandom = new SecureRandom();

  public DefaultCallbackCorrelationService(
      PlatformClock clock,
      UuidGenerator uuidGenerator,
      ObjectMapper objectMapper,
      ConnectorDefinitionRepository connectorRepo,
      ConnectorCredentialProvider credentialProvider,
      CallbackSignatureValidator signatureValidator,
      IntegrationCallbackRepository callbackRepo,
      IntegrationExecutionRepository executionRepo,
      AuditEventRepository auditRepo,
      NodeExecutionRepository nodeExecutionRepo,
      EventRepository eventRepo,
      RoutingService routingService) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.uuidGenerator = Objects.requireNonNull(uuidGenerator, "uuidGenerator");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.connectorRepo = Objects.requireNonNull(connectorRepo, "connectorRepo");
    this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider");
    this.signatureValidator = Objects.requireNonNull(signatureValidator, "signatureValidator");
    this.callbackRepo = Objects.requireNonNull(callbackRepo, "callbackRepo");
    this.executionRepo = Objects.requireNonNull(executionRepo, "executionRepo");
    this.auditRepo = Objects.requireNonNull(auditRepo, "auditRepo");
    this.nodeExecutionRepo = Objects.requireNonNull(nodeExecutionRepo, "nodeExecutionRepo");
    this.eventRepo = Objects.requireNonNull(eventRepo, "eventRepo");
    this.routingService = Objects.requireNonNull(routingService, "routingService");
  }

  @Override
  public String generateCorrelationId() {
    byte[] randomBytes = new byte[16];
    secureRandom.nextBytes(randomBytes);
    return "cbk_"
        + uuidGenerator.generate().toString().replace("-", "")
        + HexFormat.of().formatHex(randomBytes);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CallbackProcessingResult processCallback(CallbackCommand command) {
    Instant now = clock.now();
    log.info(
        "Processing callback for correlationId={}, connector={}, externalEventId={}",
        command.callbackCorrelationId(),
        command.connectorKey(),
        command.externalEventId());

    // 1. Basic parameter validation
    if (command.connectorKey() == null || command.connectorKey().isBlank()) {
      return rejectWithoutPersistence("Missing required connectorKey");
    }
    if (command.externalEventId() == null || command.externalEventId().isBlank()) {
      return rejectWithoutPersistence("Missing required externalEventId");
    }
    if (command.callbackCorrelationId() == null || command.callbackCorrelationId().isBlank()) {
      return rejectWithoutPersistence("Missing required callbackCorrelationId");
    }

    // 2. Replay & Idempotency check on (connectorKey, externalEventId)
    Optional<IntegrationCallback> existingOpt =
        callbackRepo.findByConnectorKeyAndExternalEventId(
            command.connectorKey(), command.externalEventId());
    if (existingOpt.isPresent()) {
      IntegrationCallback existing = existingOpt.get();
      log.info(
          "Duplicate callback detected for connectorKey={}, externalEventId={}, existing status={}",
          command.connectorKey(),
          command.externalEventId(),
          existing.getStatus());
      JsonNode sanitizedPayload = parseJson(existing.getSanitizedPayloadJson());
      String outcomePort =
          existing.getStatus() == IntegrationCallbackStatus.ACCEPTED ? "SUCCESS" : null;
      return new CallbackProcessingResult(
          IntegrationCallbackStatus.DUPLICATE,
          existing.getId(),
          existing.getIntegrationExecutionId(),
          null,
          outcomePort,
          "Duplicate callback; idempotently returned original result",
          sanitizedPayload);
    }

    // 3. Connector Identity Validation
    Optional<ConnectorDefinition> connectorOpt = connectorRepo.findByKey(command.connectorKey());
    if (connectorOpt.isEmpty()) {
      return persistRejected(command, null, "Unknown connector: " + command.connectorKey(), now);
    }
    ConnectorDefinition connector = connectorOpt.get();
    if (!"ACTIVE".equalsIgnoreCase(connector.getStatus())) {
      return persistRejected(
          command, null, "Connector is not active: " + command.connectorKey(), now);
    }

    // 4. Signature Validation
    Optional<String> secretOpt =
        credentialProvider.getSigningSecret(command.connectorKey(), connector.getCredentialRef());
    if (secretOpt.isEmpty()) {
      log.error(
          "No callback signing credential is configured for connector {}", command.connectorKey());
      return persistRejected(command, null, "Callback signing credential is not configured", now);
    }
    String timestampStr = command.timestamp() != null ? command.timestamp().toString() : "";
    String rawPayload =
        command.rawPayload() != null
            ? command.rawPayload()
            : (command.parsedPayload() != null ? command.parsedPayload().toString() : "");
    boolean validSig =
        signatureValidator.isValid(
            secretOpt.orElseThrow(), timestampStr, rawPayload, command.signature());
    if (!validSig) {
      log.warn("Invalid signature for callback on connector {}", command.connectorKey());
      return persistRejected(command, null, "Invalid callback signature", now);
    }

    // 5. Timestamp Freshness & Replay Protection
    if (command.timestamp() == null) {
      return persistRejected(command, null, "Missing callback timestamp", now);
    }
    Duration drift = Duration.between(command.timestamp(), now).abs();
    if (drift.toSeconds() > MAX_DRIFT_SECONDS) {
      log.warn(
          "Callback timestamp drift {}s exceeds max {}s", drift.toSeconds(), MAX_DRIFT_SECONDS);
      return persistRejected(
          command,
          null,
          "Callback timestamp outside allowable tolerance window (" + MAX_DRIFT_SECONDS + "s)",
          now);
    }

    // 6. Correlation Validation
    Optional<IntegrationExecution> executionOpt =
        executionRepo.findByCallbackCorrelationId(command.callbackCorrelationId());
    if (executionOpt.isEmpty()) {
      log.warn("Unknown correlation ID: {}", command.callbackCorrelationId());
      return persistRejected(
          command,
          null,
          "Wrong or unknown callbackCorrelationId: " + command.callbackCorrelationId(),
          now);
    }
    IntegrationExecution execution = executionOpt.get();
    if (!execution.getConnectorKey().equals(command.connectorKey())) {
      return persistRejected(
          command,
          execution.getId(),
          "Connector mismatch: expected "
              + execution.getConnectorKey()
              + " but received "
              + command.connectorKey(),
          now);
    }

    // 7. Target State Validation & Terminal-Wins / Late Callback Check
    // Lock node execution for serialized update against concurrent cancellations
    NodeExecution nodeExecution =
        nodeExecutionRepo
            .findByIdForUpdate(execution.getNodeExecutionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "NodeExecution not found: " + execution.getNodeExecutionId()));

    // The node lock serializes callback/cancel transitions. Recheck the replay key after acquiring
    // it because a concurrent callback may have committed since the optimistic pre-check above.
    Optional<IntegrationCallback> committedDuplicate =
        callbackRepo.findByConnectorKeyAndExternalEventId(
            command.connectorKey(), command.externalEventId());
    if (committedDuplicate.isPresent()) {
      IntegrationCallback original = committedDuplicate.orElseThrow();
      return new CallbackProcessingResult(
          IntegrationCallbackStatus.DUPLICATE,
          original.getId(),
          original.getIntegrationExecutionId(),
          nodeExecution.getId(),
          original.getStatus() == IntegrationCallbackStatus.ACCEPTED ? "SUCCESS" : null,
          "Duplicate callback; idempotently returned original result",
          parseJson(original.getSanitizedPayloadJson()));
    }

    Event event =
        eventRepo
            .findById(execution.getEventId())
            .orElseThrow(
                () -> new IllegalStateException("Event not found: " + execution.getEventId()));

    boolean eventTerminal = isEventTerminal(event.getStatus());
    boolean nodeTerminal = isNodeTerminal(nodeExecution.getStatus());
    boolean executionWaiting = execution.getStatus() == IntegrationExecutionStatus.WAITING_CALLBACK;

    JsonNode sanitizedPayload =
        PayloadSanitizer.sanitize(
            command.parsedPayload() != null
                ? command.parsedPayload()
                : JsonNodeFactory.instance.objectNode());
    String sanitizedPayloadStr = sanitizedPayload != null ? sanitizedPayload.toString() : "{}";

    if (eventTerminal || nodeTerminal || !executionWaiting) {
      // ──────────────────────────────────────────────────────────────────────────
      // TERMINAL-WINS: Audit late callback, do NOT resume, do NOT route downstream
      // ──────────────────────────────────────────────────────────────────────────
      log.warn(
          "Late callback received for execution={} (eventTerminal={}, nodeTerminal={}, executionStatus={})",
          execution.getId(),
          eventTerminal,
          nodeTerminal,
          execution.getStatus());

      IntegrationCallback lateCallback =
          IntegrationCallback.create(
              uuidGenerator.generate(),
              command.callbackCorrelationId(),
              execution.getId(),
              command.connectorKey(),
              command.externalEventId(),
              IntegrationCallbackStatus.LATE,
              command.signature(),
              true,
              command.timestamp(),
              now,
              now,
              sanitizedPayloadStr,
              "Callback arrived after target entered terminal state (node="
                  + nodeExecution.getStatus()
                  + ", event="
                  + event.getStatus()
                  + ")");
      callbackRepo.saveAndFlush(lateCallback);
      auditCallback(
          execution,
          lateCallback,
          "CALLBACK_LATE",
          command.correlationId(),
          command.commandId(),
          now);

      return new CallbackProcessingResult(
          IntegrationCallbackStatus.LATE,
          lateCallback.getId(),
          execution.getId(),
          nodeExecution.getId(),
          null,
          "Late callback recorded after terminal state; terminal-wins",
          sanitizedPayload);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 8. Normal Processing: mark execution complete, node complete, route downstream
    // ──────────────────────────────────────────────────────────────────────────
    String outcomePort = determineOutcomePort(sanitizedPayload);
    boolean isSuccess = "SUCCESS".equalsIgnoreCase(outcomePort);

    if (isSuccess) {
      execution.markCompleted(sanitizedPayloadStr, now);
    } else {
      execution.markFailed(IntegrationErrorCategory.HTTP_5XX, sanitizedPayloadStr, now);
    }
    executionRepo.saveAndFlush(execution);

    nodeExecution.complete(outcomePort, sanitizedPayload, now);
    nodeExecutionRepo.saveAndFlush(nodeExecution);

    IntegrationCallback acceptedCallback =
        IntegrationCallback.create(
            uuidGenerator.generate(),
            command.callbackCorrelationId(),
            execution.getId(),
            command.connectorKey(),
            command.externalEventId(),
            IntegrationCallbackStatus.ACCEPTED,
            command.signature(),
            true,
            command.timestamp(),
            now,
            now,
            sanitizedPayloadStr,
            null);
    callbackRepo.saveAndFlush(acceptedCallback);

    CorrelationId correlationId =
        command.correlationId() != null
            ? command.correlationId()
            : new CorrelationId(uuidGenerator.generate());
    CommandId commandId =
        command.commandId() != null ? command.commandId() : new CommandId(uuidGenerator.generate());

    auditCallback(execution, acceptedCallback, "CALLBACK_RECEIVED", correlationId, commandId, now);

    routingService.route(nodeExecution.getId(), correlationId, commandId);

    log.info(
        "Callback successfully accepted and downstream routed for execution={}, nodeExecution={}, outcomePort={}",
        execution.getId(),
        nodeExecution.getId(),
        outcomePort);

    return new CallbackProcessingResult(
        IntegrationCallbackStatus.ACCEPTED,
        acceptedCallback.getId(),
        execution.getId(),
        nodeExecution.getId(),
        outcomePort,
        "Callback processed successfully",
        sanitizedPayload);
  }

  private boolean isEventTerminal(EventStatus status) {
    return status == EventStatus.COMPLETED
        || status == EventStatus.FAILED
        || status == EventStatus.CANCELLED
        || status == EventStatus.TERMINATED;
  }

  private boolean isNodeTerminal(NodeExecutionStatus status) {
    return status == NodeExecutionStatus.COMPLETED
        || status == NodeExecutionStatus.FAILED
        || status == NodeExecutionStatus.CANCELLED
        || status == NodeExecutionStatus.SKIPPED;
  }

  private String determineOutcomePort(JsonNode payload) {
    if (payload == null || payload.isNull()) {
      return "SUCCESS";
    }
    String status = payload.path("status").asText("");
    if ("ERROR".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
      return "ERROR";
    }
    if (payload.has("error") && payload.get("error").asBoolean()) {
      return "ERROR";
    }
    if (payload.has("success") && !payload.get("success").asBoolean()) {
      return "ERROR";
    }
    return "SUCCESS";
  }

  private CallbackProcessingResult persistRejected(
      CallbackCommand command, UUID executionId, String errorMessage, Instant now) {
    JsonNode sanitized =
        PayloadSanitizer.sanitize(
            command.parsedPayload() != null
                ? command.parsedPayload()
                : JsonNodeFactory.instance.objectNode());
    String sanitizedStr = sanitized != null ? sanitized.toString() : "{}";

    IntegrationCallback rejected =
        IntegrationCallback.create(
            uuidGenerator.generate(),
            command.callbackCorrelationId() != null ? command.callbackCorrelationId() : "UNKNOWN",
            executionId,
            command.connectorKey() != null ? command.connectorKey() : "UNKNOWN",
            command.externalEventId() != null ? command.externalEventId() : "UNKNOWN",
            IntegrationCallbackStatus.REJECTED,
            command.signature(),
            false,
            command.timestamp(),
            now,
            now,
            sanitizedStr,
            errorMessage);
    callbackRepo.saveAndFlush(rejected);

    return new CallbackProcessingResult(
        IntegrationCallbackStatus.REJECTED,
        rejected.getId(),
        executionId,
        null,
        null,
        errorMessage,
        sanitized);
  }

  private void auditCallback(
      IntegrationExecution execution,
      IntegrationCallback callback,
      String eventType,
      CorrelationId correlationId,
      CommandId commandId,
      Instant occurredAt) {
    CorrelationId actualCorrelation =
        correlationId != null ? correlationId : new CorrelationId(uuidGenerator.generate());
    var metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("callbackId", callback.getId().toString());
    metadata.put("externalEventId", callback.getExternalEventId());
    metadata.put("status", callback.getStatus().name());
    auditRepo.save(
        AuditEvent.record(
            uuidGenerator.generate(),
            "INTEGRATION_EXECUTION",
            execution.getId(),
            eventType,
            null,
            null,
            actualCorrelation,
            commandId,
            metadata,
            occurredAt));
  }

  private CallbackProcessingResult rejectWithoutPersistence(String message) {
    return new CallbackProcessingResult(
        IntegrationCallbackStatus.REJECTED, null, null, null, null, message, null);
  }

  private JsonNode parseJson(String json) {
    if (json == null || json.isBlank()) {
      return JsonNodeFactory.instance.objectNode();
    }
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      return JsonNodeFactory.instance.objectNode();
    }
  }
}
