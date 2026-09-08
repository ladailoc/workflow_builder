package com.fpt.workflow.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "integration_callbacks")
public class IntegrationCallback {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "callback_correlation_id", nullable = false, length = 255)
  private String callbackCorrelationId;

  @Column(name = "integration_execution_id")
  private UUID integrationExecutionId;

  @Column(name = "connector_key", nullable = false, length = 100)
  private String connectorKey;

  @Column(name = "external_event_id", nullable = false, length = 255)
  private String externalEventId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 50)
  private IntegrationCallbackStatus status;

  @Column(name = "signature", length = 512)
  private String signature;

  @Column(name = "signature_valid", nullable = false)
  private boolean signatureValid;

  @Column(name = "callback_timestamp", columnDefinition = "timestamptz")
  private Instant callbackTimestamp;

  @Column(name = "received_at", nullable = false, columnDefinition = "timestamptz")
  private Instant receivedAt;

  @Column(name = "processed_at", columnDefinition = "timestamptz")
  private Instant processedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sanitized_payload_json", columnDefinition = "jsonb")
  private String sanitizedPayloadJson;

  @Column(name = "error_message")
  private String errorMessage;

  protected IntegrationCallback() {}

  public static IntegrationCallback create(
      UUID id,
      String callbackCorrelationId,
      UUID integrationExecutionId,
      String connectorKey,
      String externalEventId,
      IntegrationCallbackStatus status,
      String signature,
      boolean signatureValid,
      Instant callbackTimestamp,
      Instant receivedAt,
      Instant processedAt,
      String sanitizedPayloadJson,
      String errorMessage) {
    IntegrationCallback cb = new IntegrationCallback();
    cb.id = Objects.requireNonNull(id, "id");
    cb.callbackCorrelationId =
        Objects.requireNonNull(callbackCorrelationId, "callbackCorrelationId");
    cb.integrationExecutionId = integrationExecutionId;
    cb.connectorKey = Objects.requireNonNull(connectorKey, "connectorKey");
    cb.externalEventId = Objects.requireNonNull(externalEventId, "externalEventId");
    cb.status = Objects.requireNonNull(status, "status");
    cb.signature = signature;
    cb.signatureValid = signatureValid;
    cb.callbackTimestamp = callbackTimestamp;
    cb.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    cb.processedAt = processedAt;
    cb.sanitizedPayloadJson = sanitizedPayloadJson;
    cb.errorMessage = errorMessage;
    return cb;
  }

  public UUID getId() {
    return id;
  }

  public String getCallbackCorrelationId() {
    return callbackCorrelationId;
  }

  public UUID getIntegrationExecutionId() {
    return integrationExecutionId;
  }

  public String getConnectorKey() {
    return connectorKey;
  }

  public String getExternalEventId() {
    return externalEventId;
  }

  public IntegrationCallbackStatus getStatus() {
    return status;
  }

  public String getSignature() {
    return signature;
  }

  public boolean isSignatureValid() {
    return signatureValid;
  }

  public Instant getCallbackTimestamp() {
    return callbackTimestamp;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }

  public String getSanitizedPayloadJson() {
    return sanitizedPayloadJson;
  }

  public String getErrorMessage() {
    return errorMessage;
  }
}
