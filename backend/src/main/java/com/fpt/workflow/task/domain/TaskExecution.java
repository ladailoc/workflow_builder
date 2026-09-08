package com.fpt.workflow.task.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.domain.lifecycle.TransitionGuard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "task_executions")
public class TaskExecution {

  @Id private UUID id;

  @Column(name = "node_execution_id", nullable = false)
  private UUID nodeExecutionId;

  @Column(name = "item_execution_id")
  private UUID itemExecutionId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private TaskStatus status;

  @Column(length = 64)
  private String outcome;

  @Column(name = "assignee_id")
  private UUID assigneeId;

  @Column(name = "title_snapshot", nullable = false)
  private String titleSnapshot;

  @Column(name = "description_snapshot")
  private String descriptionSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "form_schema_json", columnDefinition = "jsonb")
  private JsonNode formSchemaJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode inputSnapshotJson;

  @Column(nullable = false)
  private int priority;

  @Column(name = "due_at", columnDefinition = "timestamptz")
  private Instant dueAt;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "started_at", columnDefinition = "timestamptz")
  private Instant startedAt;

  @Column(name = "completed_at", columnDefinition = "timestamptz")
  private Instant completedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected TaskExecution() {}

  private TaskExecution(
      UUID id,
      UUID nodeExecutionId,
      UUID itemExecutionId,
      UUID assigneeId,
      String titleSnapshot,
      String descriptionSnapshot,
      JsonNode formSchemaJson,
      JsonNode inputSnapshotJson,
      int priority,
      Instant dueAt,
      Instant createdAt) {
    if (priority < 0 || priority > 100) {
      throw new IllegalArgumentException("priority must be between 0 and 100");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.nodeExecutionId = Objects.requireNonNull(nodeExecutionId, "nodeExecutionId");
    this.itemExecutionId = itemExecutionId;
    this.status = TaskStatus.READY;
    this.assigneeId = assigneeId;
    this.titleSnapshot = TaskValues.requiredText(titleSnapshot, "titleSnapshot");
    this.descriptionSnapshot = TaskValues.optionalText(descriptionSnapshot, "descriptionSnapshot");
    this.formSchemaJson = TaskValues.nullableObject(formSchemaJson, "formSchemaJson");
    this.inputSnapshotJson = TaskValues.object(inputSnapshotJson, "inputSnapshotJson");
    this.priority = priority;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.dueAt = dueAt == null ? null : TaskValues.notBefore(dueAt, createdAt, "dueAt");
  }

  public static TaskExecution create(
      UUID id,
      UUID nodeExecutionId,
      UUID itemExecutionId,
      UUID assigneeId,
      String titleSnapshot,
      String descriptionSnapshot,
      JsonNode formSchemaJson,
      JsonNode inputSnapshotJson,
      int priority,
      Instant dueAt,
      Instant createdAt) {
    return new TaskExecution(
        id,
        nodeExecutionId,
        itemExecutionId,
        assigneeId,
        titleSnapshot,
        descriptionSnapshot,
        formSchemaJson,
        inputSnapshotJson,
        priority,
        dueAt,
        createdAt);
  }

  public void claim(UUID actorId) {
    UUID actor = Objects.requireNonNull(actorId, "actorId");
    TransitionGuard.requireAllowed(status, TaskStatus.CLAIMED, allowedTargets(status));
    if (assigneeId != null && !assigneeId.equals(actor)) {
      throw new IllegalStateException("Task is assigned to a different user");
    }
    status = TaskStatus.CLAIMED;
    assigneeId = actor;
  }

  public void start(Instant startedAt) {
    requireAssignee();
    transition(TaskStatus.IN_PROGRESS);
    if (this.startedAt == null) {
      this.startedAt = TaskValues.notBefore(startedAt, createdAt, "startedAt");
    }
  }

  public void complete(BusinessOutcome outcome, Instant completedAt) {
    requireAssignee();
    transition(TaskStatus.COMPLETED);
    this.outcome = Objects.requireNonNull(outcome, "outcome").value();
    this.completedAt = TaskValues.notBefore(completedAt, createdAt, "completedAt");
  }

  public void cancel(BusinessOutcome outcome, Instant completedAt) {
    transition(TaskStatus.CANCELLED);
    this.outcome = outcome == null ? null : outcome.value();
    this.completedAt = TaskValues.notBefore(completedAt, createdAt, "completedAt");
  }

  public void expire(BusinessOutcome outcome, Instant completedAt) {
    transition(TaskStatus.EXPIRED);
    this.outcome = outcome == null ? null : outcome.value();
    this.completedAt = TaskValues.notBefore(completedAt, createdAt, "completedAt");
  }

  public void reassign(UUID newAssigneeId) {
    if (status == TaskStatus.COMPLETED
        || status == TaskStatus.CANCELLED
        || status == TaskStatus.EXPIRED) {
      throw new IllegalStateException("A terminal task cannot be reassigned");
    }
    UUID replacement = Objects.requireNonNull(newAssigneeId, "newAssigneeId");
    if (replacement.equals(assigneeId)) throw new IllegalArgumentException("Assignee is unchanged");
    assigneeId = replacement;
  }

  private void requireAssignee() {
    if (assigneeId == null) {
      throw new IllegalStateException("Task must have an assignee");
    }
  }

  private void transition(TaskStatus target) {
    TransitionGuard.requireAllowed(status, target, allowedTargets(status));
    status = target;
  }

  private List<TaskStatus> allowedTargets(TaskStatus current) {
    return switch (current) {
      case READY ->
          List.of(
              TaskStatus.CLAIMED,
              TaskStatus.IN_PROGRESS,
              TaskStatus.COMPLETED,
              TaskStatus.CANCELLED,
              TaskStatus.EXPIRED);
      case CLAIMED ->
          List.of(
              TaskStatus.IN_PROGRESS,
              TaskStatus.COMPLETED,
              TaskStatus.CANCELLED,
              TaskStatus.EXPIRED);
      case IN_PROGRESS -> List.of(TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.EXPIRED);
      case COMPLETED, CANCELLED, EXPIRED -> List.of();
    };
  }

  public UUID getId() {
    return id;
  }

  public UUID getNodeExecutionId() {
    return nodeExecutionId;
  }

  public UUID getItemExecutionId() {
    return itemExecutionId;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public String getOutcome() {
    return outcome;
  }

  public UUID getAssigneeId() {
    return assigneeId;
  }

  public String getTitleSnapshot() {
    return titleSnapshot;
  }

  public String getDescriptionSnapshot() {
    return descriptionSnapshot;
  }

  public JsonNode getFormSchemaJson() {
    return formSchemaJson == null ? null : formSchemaJson.deepCopy();
  }

  public JsonNode getInputSnapshotJson() {
    return inputSnapshotJson.deepCopy();
  }

  public int getPriority() {
    return priority;
  }

  public Instant getDueAt() {
    return dueAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
