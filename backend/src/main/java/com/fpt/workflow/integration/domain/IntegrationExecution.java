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
@Table(name = "integration_executions")
public class IntegrationExecution {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "node_execution_id", nullable = false)
  private UUID nodeExecutionId;

  @Column(name = "connector_key", nullable = false, length = 100)
  private String connectorKey;

  @Column(name = "action_key", nullable = false, length = 100)
  private String actionKey;

  @Column(name = "action_version", nullable = false)
  private int actionVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private IntegrationExecutionStatus status;

  @Column(name = "logical_action_identity", nullable = false, length = 255)
  private String logicalActionIdentity;

  @Column(name = "idempotency_key", nullable = false, length = 255)
  private String idempotencyKey;

  @Column(name = "callback_correlation_id", length = 255)
  private String callbackCorrelationId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sanitized_request_json", columnDefinition = "jsonb")
  private String sanitizedRequestJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sanitized_response_json", columnDefinition = "jsonb")
  private String sanitizedResponseJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "error_category", length = 50)
  private IntegrationErrorCategory errorCategory;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected IntegrationExecution() {}

  public static IntegrationExecution createRunning(
      UUID id,
      UUID eventId,
      UUID nodeExecutionId,
      String connectorKey,
      String actionKey,
      int actionVersion,
      String logicalActionIdentity,
      String idempotencyKey,
      String sanitizedRequestJson,
      Instant now) {
    IntegrationExecution execution = new IntegrationExecution();
    execution.id = Objects.requireNonNull(id, "id");
    execution.eventId = Objects.requireNonNull(eventId, "eventId");
    execution.nodeExecutionId = Objects.requireNonNull(nodeExecutionId, "nodeExecutionId");
    execution.connectorKey = Objects.requireNonNull(connectorKey, "connectorKey");
    execution.actionKey = Objects.requireNonNull(actionKey, "actionKey");
    execution.actionVersion = actionVersion;
    execution.status = IntegrationExecutionStatus.RUNNING;
    execution.logicalActionIdentity =
        Objects.requireNonNull(logicalActionIdentity, "logicalActionIdentity");
    execution.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    execution.sanitizedRequestJson = sanitizedRequestJson;
    execution.createdAt = Objects.requireNonNull(now, "now");
    execution.updatedAt = now;
    return execution;
  }

  public void markCompleted(String sanitizedResponseJson, Instant now) {
    this.status = IntegrationExecutionStatus.COMPLETED;
    this.errorCategory = IntegrationErrorCategory.NONE;
    this.sanitizedResponseJson = sanitizedResponseJson;
    this.updatedAt = now;
    this.completedAt = now;
  }

  public void markFailed(
      IntegrationErrorCategory errorCategory, String sanitizedResponseJson, Instant now) {
    this.status = IntegrationExecutionStatus.FAILED;
    this.errorCategory =
        errorCategory != null ? errorCategory : IntegrationErrorCategory.CLIENT_ERROR;
    this.sanitizedResponseJson = sanitizedResponseJson;
    this.updatedAt = now;
    this.completedAt = now;
  }

  public void markWaitingCallback(String callbackCorrelationId, Instant now) {
    this.status = IntegrationExecutionStatus.WAITING_CALLBACK;
    this.callbackCorrelationId = callbackCorrelationId;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getNodeExecutionId() {
    return nodeExecutionId;
  }

  public String getConnectorKey() {
    return connectorKey;
  }

  public String getActionKey() {
    return actionKey;
  }

  public int getActionVersion() {
    return actionVersion;
  }

  public IntegrationExecutionStatus getStatus() {
    return status;
  }

  public String getLogicalActionIdentity() {
    return logicalActionIdentity;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getCallbackCorrelationId() {
    return callbackCorrelationId;
  }

  public String getSanitizedRequestJson() {
    return sanitizedRequestJson;
  }

  public String getSanitizedResponseJson() {
    return sanitizedResponseJson;
  }

  public IntegrationErrorCategory getErrorCategory() {
    return errorCategory;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
