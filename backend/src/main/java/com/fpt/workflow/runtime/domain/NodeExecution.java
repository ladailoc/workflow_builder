package com.fpt.workflow.runtime.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
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
@Table(name = "node_executions")
public class NodeExecution {

  @Id private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "node_definition_id", nullable = false)
  private UUID nodeDefinitionId;

  @Column(name = "activation_key", nullable = false, length = 512)
  private String activationKey;

  @Column(name = "cycle_id", nullable = false)
  private UUID cycleId;

  @Column(nullable = false)
  private int iteration;

  @Column(name = "path_token", nullable = false, length = 256)
  private String pathToken;

  @Column(name = "item_token", length = 256)
  private String itemToken;

  @Column(name = "split_scope_id")
  private UUID splitScopeId;

  @Column(name = "join_scope_id")
  private UUID joinScopeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private NodeExecutionStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "wait_reason", length = 64)
  private RuntimeWaitReason waitReason;

  @Column(name = "outcome_port", length = 128)
  private String outcomePort;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "input_json", columnDefinition = "jsonb")
  private JsonNode inputJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "output_json", columnDefinition = "jsonb")
  private JsonNode outputJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "error_json", columnDefinition = "jsonb")
  private JsonNode errorJson;

  @Column(name = "started_ticket_revision_id", nullable = false)
  private UUID startedTicketRevisionId;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "started_at", columnDefinition = "timestamptz")
  private Instant startedAt;

  @Column(name = "ended_at", columnDefinition = "timestamptz")
  private Instant endedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected NodeExecution() {}

  private NodeExecution(
      UUID id,
      UUID eventId,
      UUID nodeDefinitionId,
      String activationKey,
      UUID cycleId,
      int iteration,
      String pathToken,
      String itemToken,
      UUID splitScopeId,
      UUID joinScopeId,
      JsonNode inputJson,
      UUID startedTicketRevisionId,
      Instant createdAt) {
    if (iteration < 0) {
      throw new IllegalArgumentException("iteration must not be negative");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.nodeDefinitionId = Objects.requireNonNull(nodeDefinitionId, "nodeDefinitionId");
    this.activationKey = RuntimeValues.requiredText(activationKey, "activationKey");
    this.cycleId = Objects.requireNonNull(cycleId, "cycleId");
    this.iteration = iteration;
    this.pathToken = RuntimeValues.requiredText(pathToken, "pathToken");
    this.itemToken = RuntimeValues.optionalText(itemToken, "itemToken");
    this.splitScopeId = splitScopeId;
    this.joinScopeId = joinScopeId;
    this.inputJson = RuntimeValues.nullableObject(inputJson, "inputJson");
    this.startedTicketRevisionId =
        Objects.requireNonNull(startedTicketRevisionId, "startedTicketRevisionId");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.status = NodeExecutionStatus.CREATED;
  }

  public static NodeExecution create(
      UUID id,
      UUID eventId,
      UUID nodeDefinitionId,
      String activationKey,
      UUID cycleId,
      int iteration,
      String pathToken,
      String itemToken,
      UUID splitScopeId,
      UUID joinScopeId,
      JsonNode inputJson,
      UUID startedTicketRevisionId,
      Instant createdAt) {
    return new NodeExecution(
        id,
        eventId,
        nodeDefinitionId,
        activationKey,
        cycleId,
        iteration,
        pathToken,
        itemToken,
        splitScopeId,
        joinScopeId,
        inputJson,
        startedTicketRevisionId,
        createdAt);
  }

  public void markReady() {
    transition(NodeExecutionStatus.READY);
  }

  public void start(Instant startedAt) {
    transition(NodeExecutionStatus.RUNNING);
    this.waitReason = null;
    if (this.startedAt == null) {
      this.startedAt = RuntimeValues.notBefore(startedAt, createdAt, "startedAt");
    }
  }

  public void waitFor(RuntimeWaitReason reason) {
    transition(NodeExecutionStatus.WAITING);
    this.waitReason = Objects.requireNonNull(reason, "reason");
  }

  public void complete(String outcomePort, JsonNode outputJson, Instant endedAt) {
    transition(NodeExecutionStatus.COMPLETED);
    this.outcomePort = RuntimeValues.key(outcomePort, "outcomePort");
    this.outputJson = RuntimeValues.object(outputJson, "outputJson");
    this.waitReason = null;
    this.endedAt = RuntimeValues.notBefore(endedAt, createdAt, "endedAt");
  }

  public void fail(JsonNode errorJson, Instant endedAt) {
    transition(NodeExecutionStatus.FAILED);
    this.errorJson = RuntimeValues.object(errorJson, "errorJson");
    this.waitReason = null;
    this.endedAt = RuntimeValues.notBefore(endedAt, createdAt, "endedAt");
  }

  public void cancel(Instant endedAt) {
    transition(NodeExecutionStatus.CANCELLED);
    this.waitReason = null;
    this.endedAt = RuntimeValues.notBefore(endedAt, createdAt, "endedAt");
  }

  public void skip(Instant endedAt) {
    transition(NodeExecutionStatus.SKIPPED);
    this.endedAt = RuntimeValues.notBefore(endedAt, createdAt, "endedAt");
  }

  private void transition(NodeExecutionStatus target) {
    TransitionGuard.requireAllowed(status, target, allowedTargets(status));
    this.status = target;
  }

  private List<NodeExecutionStatus> allowedTargets(NodeExecutionStatus current) {
    return switch (current) {
      case CREATED ->
          List.of(
              NodeExecutionStatus.READY,
              NodeExecutionStatus.FAILED,
              NodeExecutionStatus.CANCELLED,
              NodeExecutionStatus.SKIPPED);
      case READY ->
          List.of(
              NodeExecutionStatus.RUNNING,
              NodeExecutionStatus.FAILED,
              NodeExecutionStatus.CANCELLED,
              NodeExecutionStatus.SKIPPED);
      case RUNNING ->
          List.of(
              NodeExecutionStatus.WAITING,
              NodeExecutionStatus.COMPLETED,
              NodeExecutionStatus.FAILED,
              NodeExecutionStatus.CANCELLED);
      case WAITING ->
          List.of(
              NodeExecutionStatus.RUNNING,
              NodeExecutionStatus.COMPLETED,
              NodeExecutionStatus.FAILED,
              NodeExecutionStatus.CANCELLED);
      case COMPLETED, FAILED, CANCELLED, SKIPPED -> List.of();
    };
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getNodeDefinitionId() {
    return nodeDefinitionId;
  }

  public String getActivationKey() {
    return activationKey;
  }

  public UUID getCycleId() {
    return cycleId;
  }

  public int getIteration() {
    return iteration;
  }

  public String getPathToken() {
    return pathToken;
  }

  public String getItemToken() {
    return itemToken;
  }

  public UUID getSplitScopeId() {
    return splitScopeId;
  }

  public UUID getJoinScopeId() {
    return joinScopeId;
  }

  public NodeExecutionStatus getStatus() {
    return status;
  }

  public RuntimeWaitReason getWaitReason() {
    return waitReason;
  }

  public String getOutcomePort() {
    return outcomePort;
  }

  public JsonNode getInputJson() {
    return inputJson == null ? null : inputJson.deepCopy();
  }

  public JsonNode getOutputJson() {
    return outputJson == null ? null : outputJson.deepCopy();
  }

  public JsonNode getErrorJson() {
    return errorJson == null ? null : errorJson.deepCopy();
  }

  public UUID getStartedTicketRevisionId() {
    return startedTicketRevisionId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
