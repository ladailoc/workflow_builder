package com.fpt.workflow.resolver.domain;

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
@Table(name = "participant_snapshots")
public class ParticipantSnapshot {

  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9._-]{0,127}");

  @Id private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "node_execution_id", nullable = false)
  private UUID nodeExecutionId;

  @Column(name = "item_execution_id")
  private UUID itemExecutionId;

  @Column(name = "resolver_type", nullable = false, length = 128)
  private String resolverType;

  @Column(name = "resolver_config_hash", nullable = false, length = 256)
  private String resolverConfigHash;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "resolver_config_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode resolverConfigJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "resolution_status", nullable = false, length = 32)
  private ParticipantResolutionStatus resolutionStatus;

  @Column(name = "subject_type", length = 128)
  private String subjectType;

  @Column(name = "subject_ref_id")
  private UUID subjectRefId;

  @Column(name = "resolved_user_id")
  private UUID resolvedUserId;

  @Column(name = "participant_role", nullable = false, length = 128)
  private String participantRole;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "snapshot_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode snapshotJson;

  @Column(name = "resolved_at", nullable = false, columnDefinition = "timestamptz")
  private Instant resolvedAt;

  protected ParticipantSnapshot() {}

  private ParticipantSnapshot(
      UUID id,
      UUID eventId,
      UUID nodeExecutionId,
      UUID itemExecutionId,
      String resolverType,
      String resolverConfigHash,
      JsonNode resolverConfigJson,
      ParticipantResolutionStatus resolutionStatus,
      String subjectType,
      UUID subjectRefId,
      UUID resolvedUserId,
      String participantRole,
      JsonNode snapshotJson,
      Instant resolvedAt) {
    if ((subjectType == null) != (subjectRefId == null)) {
      throw new IllegalArgumentException(
          "subjectType and subjectRefId must both be present or absent");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.nodeExecutionId = Objects.requireNonNull(nodeExecutionId, "nodeExecutionId");
    this.itemExecutionId = itemExecutionId;
    this.resolverType = key(resolverType, "resolverType");
    this.resolverConfigHash = requiredText(resolverConfigHash, "resolverConfigHash");
    this.resolverConfigJson = object(resolverConfigJson, "resolverConfigJson");
    this.resolutionStatus = Objects.requireNonNull(resolutionStatus, "resolutionStatus");
    this.subjectType = subjectType == null ? null : key(subjectType, "subjectType");
    this.subjectRefId = subjectRefId;
    this.resolvedUserId = resolvedUserId;
    this.participantRole = key(participantRole, "participantRole");
    this.snapshotJson = object(snapshotJson, "snapshotJson");
    this.resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    validateResolution();
  }

  public static ParticipantSnapshot create(
      UUID id,
      UUID eventId,
      UUID nodeExecutionId,
      UUID itemExecutionId,
      String resolverType,
      String resolverConfigHash,
      JsonNode resolverConfigJson,
      ParticipantResolutionStatus resolutionStatus,
      String subjectType,
      UUID subjectRefId,
      UUID resolvedUserId,
      String participantRole,
      JsonNode snapshotJson,
      Instant resolvedAt) {
    return new ParticipantSnapshot(
        id,
        eventId,
        nodeExecutionId,
        itemExecutionId,
        resolverType,
        resolverConfigHash,
        resolverConfigJson,
        resolutionStatus,
        subjectType,
        subjectRefId,
        resolvedUserId,
        participantRole,
        snapshotJson,
        resolvedAt);
  }

  private void validateResolution() {
    if ((resolutionStatus == ParticipantResolutionStatus.RESOLVED) != (resolvedUserId != null)) {
      throw new IllegalArgumentException("Only RESOLVED snapshots carry a resolved user");
    }
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
    Objects.requireNonNull(value, field);
    if (!value.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return value.deepCopy();
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

  public UUID getItemExecutionId() {
    return itemExecutionId;
  }

  public String getResolverType() {
    return resolverType;
  }

  public String getResolverConfigHash() {
    return resolverConfigHash;
  }

  public JsonNode getResolverConfigJson() {
    return resolverConfigJson.deepCopy();
  }

  public ParticipantResolutionStatus getResolutionStatus() {
    return resolutionStatus;
  }

  public String getSubjectType() {
    return subjectType;
  }

  public UUID getSubjectRefId() {
    return subjectRefId;
  }

  public UUID getResolvedUserId() {
    return resolvedUserId;
  }

  public String getParticipantRole() {
    return participantRole;
  }

  public JsonNode getSnapshotJson() {
    return snapshotJson.deepCopy();
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }
}
