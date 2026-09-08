package com.fpt.workflow.operations.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "audit_events")
public class AuditEvent {

  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9._-]{0,127}");

  @Id private UUID id;

  @Column(name = "aggregate_type", nullable = false, length = 128)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false, length = 128)
  private String eventType;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(name = "principal_id")
  private UUID principalId;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "command_id")
  private UUID commandId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode metadataJson;

  @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamptz")
  private Instant occurredAt;

  protected AuditEvent() {}

  private AuditEvent(
      UUID id,
      String aggregateType,
      UUID aggregateId,
      String eventType,
      UUID actorId,
      UUID principalId,
      CorrelationId correlationId,
      CommandId commandId,
      JsonNode metadataJson,
      Instant occurredAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.aggregateType = key(aggregateType, "aggregateType");
    this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
    this.eventType = key(eventType, "eventType");
    this.actorId = actorId;
    this.principalId = principalId;
    this.correlationId = Objects.requireNonNull(correlationId, "correlationId").value();
    this.commandId = commandId == null ? null : commandId.value();
    this.metadataJson = object(metadataJson);
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
  }

  public static AuditEvent record(
      UUID id,
      String aggregateType,
      UUID aggregateId,
      String eventType,
      UUID actorId,
      UUID principalId,
      CorrelationId correlationId,
      CommandId commandId,
      JsonNode metadataJson,
      Instant occurredAt) {
    return new AuditEvent(
        id,
        aggregateType,
        aggregateId,
        eventType,
        actorId,
        principalId,
        correlationId,
        commandId,
        metadataJson,
        occurredAt);
  }

  private static String key(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!KEY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " has an invalid technical key");
    }
    return normalized;
  }

  private static JsonNode object(JsonNode value) {
    Objects.requireNonNull(value, "metadataJson");
    if (!value.isObject()) {
      throw new IllegalArgumentException("metadataJson must be a JSON object");
    }
    return value.deepCopy();
  }

  public UUID getId() {
    return id;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
  }

  public UUID getActorId() {
    return actorId;
  }

  public UUID getPrincipalId() {
    return principalId;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public UUID getCommandId() {
    return commandId;
  }

  public JsonNode getMetadataJson() {
    return metadataJson.deepCopy();
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
