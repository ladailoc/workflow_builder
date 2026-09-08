package com.fpt.workflow.task.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@IdClass(TaskCandidateId.class)
@Table(name = "task_candidates")
public class TaskCandidate {

  @Id
  @Column(name = "task_id")
  private UUID taskId;

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "source_type", nullable = false, length = 128)
  private String sourceType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "source_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode sourceSnapshotJson;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  protected TaskCandidate() {}

  private TaskCandidate(
      UUID taskId, UUID userId, String sourceType, JsonNode sourceSnapshotJson, Instant createdAt) {
    this.taskId = Objects.requireNonNull(taskId, "taskId");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.sourceType = TaskValues.key(sourceType, "sourceType");
    this.sourceSnapshotJson = TaskValues.object(sourceSnapshotJson, "sourceSnapshotJson");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public static TaskCandidate create(
      UUID taskId, UUID userId, String sourceType, JsonNode sourceSnapshotJson, Instant createdAt) {
    return new TaskCandidate(taskId, userId, sourceType, sourceSnapshotJson, createdAt);
  }

  public UUID getTaskId() {
    return taskId;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getSourceType() {
    return sourceType;
  }

  public JsonNode getSourceSnapshotJson() {
    return sourceSnapshotJson.deepCopy();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
