package com.fpt.workflow.task.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
@Table(name = "task_assignment_history")
public class TaskAssignmentHistory {

  @Id private UUID id;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Enumerated(EnumType.STRING)
  @Column(name = "action_type", nullable = false, length = 32)
  private TaskAssignmentAction actionType;

  @Column(name = "from_user_id")
  private UUID fromUserId;

  @Column(name = "to_user_id")
  private UUID toUserId;

  @Column(name = "actor_id", nullable = false)
  private UUID actorId;

  @Column private String reason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode metadataJson;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  protected TaskAssignmentHistory() {}

  private TaskAssignmentHistory(
      UUID id,
      UUID taskId,
      TaskAssignmentAction actionType,
      UUID fromUserId,
      UUID toUserId,
      UUID actorId,
      String reason,
      JsonNode metadataJson,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.taskId = Objects.requireNonNull(taskId, "taskId");
    this.actionType = Objects.requireNonNull(actionType, "actionType");
    this.fromUserId = fromUserId;
    this.toUserId = toUserId;
    this.actorId = Objects.requireNonNull(actorId, "actorId");
    this.reason = TaskValues.optionalText(reason, "reason");
    this.metadataJson = TaskValues.object(metadataJson, "metadataJson");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    validateActionShape();
  }

  public static TaskAssignmentHistory create(
      UUID id,
      UUID taskId,
      TaskAssignmentAction actionType,
      UUID fromUserId,
      UUID toUserId,
      UUID actorId,
      String reason,
      JsonNode metadataJson,
      Instant createdAt) {
    return new TaskAssignmentHistory(
        id, taskId, actionType, fromUserId, toUserId, actorId, reason, metadataJson, createdAt);
  }

  private void validateActionShape() {
    boolean valid =
        switch (actionType) {
          case ASSIGN -> fromUserId == null && toUserId != null;
          case CLAIM -> toUserId != null;
          case UNCLAIM -> fromUserId != null && toUserId == null;
          case REASSIGN ->
              fromUserId != null
                  && toUserId != null
                  && !fromUserId.equals(toUserId)
                  && reason != null;
        };
    if (!valid) {
      throw new IllegalArgumentException("Invalid assignment history shape for " + actionType);
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public TaskAssignmentAction getActionType() {
    return actionType;
  }

  public UUID getFromUserId() {
    return fromUserId;
  }

  public UUID getToUserId() {
    return toUserId;
  }

  public UUID getActorId() {
    return actorId;
  }

  public String getReason() {
    return reason;
  }

  public JsonNode getMetadataJson() {
    return metadataJson.deepCopy();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
