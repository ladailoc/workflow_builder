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
@Table(name = "integration_attempts")
public class IntegrationAttempt {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "integration_execution_id", nullable = false)
  private UUID integrationExecutionId;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private AttemptStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sanitized_request_json", columnDefinition = "jsonb")
  private String sanitizedRequestJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "sanitized_response_json", columnDefinition = "jsonb")
  private String sanitizedResponseJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "error_category", length = 50)
  private IntegrationErrorCategory errorCategory;

  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected IntegrationAttempt() {}

  public static IntegrationAttempt createRunning(
      UUID id,
      UUID integrationExecutionId,
      int attemptNumber,
      String sanitizedRequestJson,
      Instant startedAt) {
    IntegrationAttempt attempt = new IntegrationAttempt();
    attempt.id = Objects.requireNonNull(id, "id");
    attempt.integrationExecutionId =
        Objects.requireNonNull(integrationExecutionId, "integrationExecutionId");
    attempt.attemptNumber = attemptNumber;
    attempt.status = AttemptStatus.RUNNING;
    attempt.sanitizedRequestJson = sanitizedRequestJson;
    attempt.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    return attempt;
  }

  public void markSuccess(String sanitizedResponseJson, Instant completedAt) {
    this.status = AttemptStatus.SUCCESS;
    this.errorCategory = IntegrationErrorCategory.NONE;
    this.sanitizedResponseJson = sanitizedResponseJson;
    this.completedAt = completedAt;
  }

  public void markFailure(
      IntegrationErrorCategory errorCategory,
      String errorMessage,
      String sanitizedResponseJson,
      Instant completedAt) {
    this.status = AttemptStatus.FAILURE;
    this.errorCategory =
        errorCategory != null ? errorCategory : IntegrationErrorCategory.CLIENT_ERROR;
    this.errorMessage = errorMessage;
    this.sanitizedResponseJson = sanitizedResponseJson;
    this.completedAt = completedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getIntegrationExecutionId() {
    return integrationExecutionId;
  }

  public int getAttemptNumber() {
    return attemptNumber;
  }

  public AttemptStatus getStatus() {
    return status;
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

  public String getErrorMessage() {
    return errorMessage;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
