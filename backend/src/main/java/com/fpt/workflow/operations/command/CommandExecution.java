package com.fpt.workflow.operations.command;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "command_executions")
public class CommandExecution {

  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9._-]{0,127}");

  @Id private UUID id;

  @Column(name = "scope_type", nullable = false, length = 128)
  private String scopeType;

  @Column(name = "scope_id", nullable = false)
  private UUID scopeId;

  @Column(name = "command_id", nullable = false)
  private UUID commandId;

  @Column(name = "command_type", nullable = false, length = 128)
  private String commandType;

  @Column(name = "actor_id", nullable = false)
  private UUID actorId;

  @Column(name = "expected_version")
  private Long expectedVersion;

  @Column(name = "request_hash", nullable = false, length = 256)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private CommandExecutionStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "result_json", columnDefinition = "jsonb")
  private JsonNode resultJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "error_json", columnDefinition = "jsonb")
  private JsonNode errorJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "result_metadata_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode resultMetadataJson;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "completed_at", columnDefinition = "timestamptz")
  private Instant completedAt;

  protected CommandExecution() {}

  private CommandExecution(
      UUID id,
      String scopeType,
      UUID scopeId,
      UUID commandId,
      String commandType,
      UUID actorId,
      Long expectedVersion,
      String requestHash,
      JsonNode resultMetadataJson,
      Instant createdAt) {
    if (expectedVersion != null && expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.scopeType = key(scopeType, "scopeType");
    this.scopeId = Objects.requireNonNull(scopeId, "scopeId");
    this.commandId = Objects.requireNonNull(commandId, "commandId");
    this.commandType = key(commandType, "commandType");
    this.actorId = Objects.requireNonNull(actorId, "actorId");
    this.expectedVersion = expectedVersion;
    this.requestHash = requiredText(requestHash, "requestHash");
    this.status = CommandExecutionStatus.IN_PROGRESS;
    this.resultMetadataJson = object(resultMetadataJson, "resultMetadataJson");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public static CommandExecution start(
      UUID id,
      String scopeType,
      UUID scopeId,
      UUID commandId,
      String commandType,
      UUID actorId,
      Long expectedVersion,
      String requestHash,
      JsonNode resultMetadataJson,
      Instant createdAt) {
    return new CommandExecution(
        id,
        scopeType,
        scopeId,
        commandId,
        commandType,
        actorId,
        expectedVersion,
        requestHash,
        resultMetadataJson,
        createdAt);
  }

  public void succeed(JsonNode resultJson, JsonNode resultMetadataJson, Instant completedAt) {
    requireInProgress();
    this.resultJson = copy(resultJson, "resultJson");
    this.resultMetadataJson = object(resultMetadataJson, "resultMetadataJson");
    this.completedAt = notBefore(completedAt);
    this.status = CommandExecutionStatus.SUCCEEDED;
  }

  public void fail(JsonNode errorJson, JsonNode resultMetadataJson, Instant completedAt) {
    requireInProgress();
    this.errorJson = copy(errorJson, "errorJson");
    this.resultMetadataJson = object(resultMetadataJson, "resultMetadataJson");
    this.completedAt = notBefore(completedAt);
    this.status = CommandExecutionStatus.FAILED;
  }

  public boolean matches(
      String commandType, UUID actorId, Long expectedVersion, String requestHash) {
    return this.commandType.equals(key(commandType, "commandType"))
        && this.actorId.equals(Objects.requireNonNull(actorId, "actorId"))
        && Objects.equals(this.expectedVersion, expectedVersion)
        && this.requestHash.equals(requiredText(requestHash, "requestHash"));
  }

  private void requireInProgress() {
    if (status != CommandExecutionStatus.IN_PROGRESS) {
      throw new IllegalStateException("Terminal CommandExecution is immutable");
    }
  }

  private Instant notBefore(Instant value) {
    Instant timestamp = Objects.requireNonNull(value, "completedAt");
    if (timestamp.isBefore(createdAt)) {
      throw new IllegalArgumentException("completedAt must not be before createdAt");
    }
    return timestamp;
  }

  private static String key(String value, String field) {
    String normalized = requiredText(value, field).toUpperCase(Locale.ROOT);
    if (!KEY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " has an invalid technical key");
    }
    return normalized;
  }

  private static String requiredText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  private static JsonNode object(JsonNode value, String field) {
    JsonNode copy = copy(value, field);
    if (!copy.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return copy;
  }

  private static JsonNode copy(JsonNode value, String field) {
    return Objects.requireNonNull(value, field).deepCopy();
  }

  public UUID getId() {
    return id;
  }

  public String getScopeType() {
    return scopeType;
  }

  public UUID getScopeId() {
    return scopeId;
  }

  public UUID getCommandId() {
    return commandId;
  }

  public String getCommandType() {
    return commandType;
  }

  public UUID getActorId() {
    return actorId;
  }

  public Long getExpectedVersion() {
    return expectedVersion;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public CommandExecutionStatus getStatus() {
    return status;
  }

  public JsonNode getResultJson() {
    return resultJson == null ? null : resultJson.deepCopy();
  }

  public JsonNode getErrorJson() {
    return errorJson == null ? null : errorJson.deepCopy();
  }

  public JsonNode getResultMetadataJson() {
    return resultMetadataJson.deepCopy();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
