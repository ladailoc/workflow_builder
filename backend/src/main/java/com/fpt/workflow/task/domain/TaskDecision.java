package com.fpt.workflow.task.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "task_decisions")
public class TaskDecision {

  @Id private UUID id;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(name = "command_id", nullable = false)
  private UUID commandId;

  @Column(name = "actor_id", nullable = false)
  private UUID actorId;

  @Column(name = "principal_id")
  private UUID principalId;

  @Column(nullable = false, length = 64)
  private String outcome;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "form_data_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode formDataJson;

  @Column private String comment;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  protected TaskDecision() {}

  private TaskDecision(
      UUID id,
      UUID taskId,
      UUID commandId,
      UUID actorId,
      UUID principalId,
      BusinessOutcome outcome,
      JsonNode formDataJson,
      String comment,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.taskId = Objects.requireNonNull(taskId, "taskId");
    this.commandId = Objects.requireNonNull(commandId, "commandId");
    this.actorId = Objects.requireNonNull(actorId, "actorId");
    this.principalId = principalId;
    this.outcome = Objects.requireNonNull(outcome, "outcome").value();
    this.formDataJson = TaskValues.object(formDataJson, "formDataJson");
    this.comment = TaskValues.optionalText(comment, "comment");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public static TaskDecision create(
      UUID id,
      UUID taskId,
      UUID commandId,
      UUID actorId,
      UUID principalId,
      BusinessOutcome outcome,
      JsonNode formDataJson,
      String comment,
      Instant createdAt) {
    return new TaskDecision(
        id, taskId, commandId, actorId, principalId, outcome, formDataJson, comment, createdAt);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public UUID getCommandId() {
    return commandId;
  }

  public UUID getActorId() {
    return actorId;
  }

  public UUID getPrincipalId() {
    return principalId;
  }

  public String getOutcome() {
    return outcome;
  }

  public JsonNode getFormDataJson() {
    return formDataJson.deepCopy();
  }

  public String getComment() {
    return comment;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
