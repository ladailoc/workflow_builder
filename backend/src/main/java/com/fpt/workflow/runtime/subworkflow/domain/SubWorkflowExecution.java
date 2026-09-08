package com.fpt.workflow.runtime.subworkflow.domain;

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
@Table(name = "sub_workflow_executions")
public class SubWorkflowExecution {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "parent_event_id", nullable = false)
  private UUID parentEventId;

  @Column(name = "parent_node_execution_id", nullable = false)
  private UUID parentNodeExecutionId;

  @Column(name = "child_event_id", nullable = false)
  private UUID childEventId;

  @Column(name = "child_workflow_definition_id", nullable = false)
  private UUID childWorkflowDefinitionId;

  @Column(name = "child_workflow_version_id", nullable = false)
  private UUID childWorkflowVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "execution_mode", nullable = false, length = 50)
  private SubWorkflowExecutionMode executionMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "cancellation_policy", nullable = false, length = 50)
  private SubWorkflowCancellationPolicy cancellationPolicy;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 50)
  private SubWorkflowExecutionStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "input_snapshot_json", columnDefinition = "jsonb")
  private String inputSnapshotJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "output_snapshot_json", columnDefinition = "jsonb")
  private String outputSnapshotJson;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "completed_at", columnDefinition = "timestamptz")
  private Instant completedAt;

  protected SubWorkflowExecution() {}

  public static SubWorkflowExecution create(
      UUID id,
      UUID parentEventId,
      UUID parentNodeExecutionId,
      UUID childEventId,
      UUID childWorkflowDefinitionId,
      UUID childWorkflowVersionId,
      SubWorkflowExecutionMode executionMode,
      SubWorkflowCancellationPolicy cancellationPolicy,
      String inputSnapshotJson,
      Instant createdAt) {
    SubWorkflowExecution exec = new SubWorkflowExecution();
    exec.id = Objects.requireNonNull(id, "id");
    exec.parentEventId = Objects.requireNonNull(parentEventId, "parentEventId");
    exec.parentNodeExecutionId =
        Objects.requireNonNull(parentNodeExecutionId, "parentNodeExecutionId");
    exec.childEventId = Objects.requireNonNull(childEventId, "childEventId");
    exec.childWorkflowDefinitionId =
        Objects.requireNonNull(childWorkflowDefinitionId, "childWorkflowDefinitionId");
    exec.childWorkflowVersionId =
        Objects.requireNonNull(childWorkflowVersionId, "childWorkflowVersionId");
    exec.executionMode = Objects.requireNonNull(executionMode, "executionMode");
    exec.cancellationPolicy = Objects.requireNonNull(cancellationPolicy, "cancellationPolicy");
    exec.status = SubWorkflowExecutionStatus.RUNNING;
    exec.inputSnapshotJson = inputSnapshotJson;
    exec.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    return exec;
  }

  public void markCompleted(String outputSnapshotJson, Instant completedAt) {
    this.status = SubWorkflowExecutionStatus.COMPLETED;
    this.outputSnapshotJson = outputSnapshotJson;
    this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
  }

  public void markFailed(String errorSnapshotJson, Instant completedAt) {
    this.status = SubWorkflowExecutionStatus.FAILED;
    this.outputSnapshotJson = errorSnapshotJson;
    this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
  }

  public void markCancelled(Instant completedAt) {
    this.status = SubWorkflowExecutionStatus.CANCELLED;
    this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
  }

  public UUID getId() {
    return id;
  }

  public UUID getParentEventId() {
    return parentEventId;
  }

  public UUID getParentNodeExecutionId() {
    return parentNodeExecutionId;
  }

  public UUID getChildEventId() {
    return childEventId;
  }

  public UUID getChildWorkflowDefinitionId() {
    return childWorkflowDefinitionId;
  }

  public UUID getChildWorkflowVersionId() {
    return childWorkflowVersionId;
  }

  public SubWorkflowExecutionMode getExecutionMode() {
    return executionMode;
  }

  public SubWorkflowCancellationPolicy getCancellationPolicy() {
    return cancellationPolicy;
  }

  public SubWorkflowExecutionStatus getStatus() {
    return status;
  }

  public String getInputSnapshotJson() {
    return inputSnapshotJson;
  }

  public String getOutputSnapshotJson() {
    return outputSnapshotJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
