package com.fpt.workflow.runtime.routing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A durable token representing one downstream activation intent. Created atomically with the
 * RoutingDecision before any NodeActivationService call. Idempotent via UNIQUE (activation_key).
 * Crash-recovery replays PENDING tokens; ACTIVATED tokens skip re-execution.
 */
@Entity
@Table(name = "activation_tokens")
public class ActivationToken {

  @Id private UUID id;

  @Column(name = "routing_decision_id", nullable = false)
  private UUID routingDecisionId;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "source_node_execution_id", nullable = false)
  private UUID sourceNodeExecutionId;

  @Column(name = "edge_id", nullable = false)
  private UUID edgeId;

  @Column(name = "target_node_definition_id", nullable = false)
  private UUID targetNodeDefinitionId;

  @Column(name = "activation_key", nullable = false, length = 512, unique = true)
  private String activationKey;

  @Column(name = "path_token", nullable = false, length = 256)
  private String pathToken;

  @Column(name = "cycle_id", nullable = false)
  private UUID cycleId;

  @Column(nullable = false)
  private int iteration;

  @Column(name = "item_token", length = 256)
  private String itemToken;

  @Column(name = "split_scope_id")
  private UUID splitScopeId;

  @Column(name = "join_scope_id")
  private UUID joinScopeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ActivationTokenStatus status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "activated_at", columnDefinition = "timestamptz")
  private Instant activatedAt;

  protected ActivationToken() {}

  private ActivationToken(
      UUID id,
      UUID routingDecisionId,
      UUID eventId,
      UUID sourceNodeExecutionId,
      UUID edgeId,
      UUID targetNodeDefinitionId,
      String activationKey,
      String pathToken,
      UUID cycleId,
      int iteration,
      String itemToken,
      UUID splitScopeId,
      UUID joinScopeId,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.routingDecisionId = Objects.requireNonNull(routingDecisionId, "routingDecisionId");
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.sourceNodeExecutionId =
        Objects.requireNonNull(sourceNodeExecutionId, "sourceNodeExecutionId");
    this.edgeId = Objects.requireNonNull(edgeId, "edgeId");
    this.targetNodeDefinitionId =
        Objects.requireNonNull(targetNodeDefinitionId, "targetNodeDefinitionId");
    if (activationKey == null || activationKey.isBlank()) {
      throw new IllegalArgumentException("activationKey must not be blank");
    }
    this.activationKey = activationKey;
    if (pathToken == null || pathToken.isBlank()) {
      throw new IllegalArgumentException("pathToken must not be blank");
    }
    this.pathToken = pathToken;
    this.cycleId = Objects.requireNonNull(cycleId, "cycleId");
    if (iteration < 0) throw new IllegalArgumentException("iteration must not be negative");
    this.iteration = iteration;
    this.itemToken = itemToken;
    this.splitScopeId = splitScopeId;
    this.joinScopeId = joinScopeId;
    this.status = ActivationTokenStatus.PENDING;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public static ActivationToken pending(
      UUID id,
      UUID routingDecisionId,
      UUID eventId,
      UUID sourceNodeExecutionId,
      UUID edgeId,
      UUID targetNodeDefinitionId,
      String activationKey,
      String pathToken,
      UUID cycleId,
      int iteration,
      String itemToken,
      UUID splitScopeId,
      UUID joinScopeId,
      Instant createdAt) {
    return new ActivationToken(
        id,
        routingDecisionId,
        eventId,
        sourceNodeExecutionId,
        edgeId,
        targetNodeDefinitionId,
        activationKey,
        pathToken,
        cycleId,
        iteration,
        itemToken,
        splitScopeId,
        joinScopeId,
        createdAt);
  }

  public static ActivationToken pending(
      UUID id,
      UUID routingDecisionId,
      UUID eventId,
      UUID sourceNodeExecutionId,
      UUID edgeId,
      UUID targetNodeDefinitionId,
      String activationKey,
      String pathToken,
      UUID cycleId,
      String itemToken,
      UUID splitScopeId,
      UUID joinScopeId,
      Instant createdAt) {
    return pending(
        id,
        routingDecisionId,
        eventId,
        sourceNodeExecutionId,
        edgeId,
        targetNodeDefinitionId,
        activationKey,
        pathToken,
        cycleId,
        0,
        itemToken,
        splitScopeId,
        joinScopeId,
        createdAt);
  }

  public void markActivated(Instant now) {
    if (this.status != ActivationTokenStatus.PENDING) {
      throw new IllegalStateException("Can only activate a PENDING token, was: " + this.status);
    }
    this.status = ActivationTokenStatus.ACTIVATED;
    this.activatedAt = Objects.requireNonNull(now, "now");
  }

  public void cancel(Instant now) {
    if (this.status != ActivationTokenStatus.PENDING) {
      throw new IllegalStateException("Can only cancel a PENDING token, was: " + this.status);
    }
    this.status = ActivationTokenStatus.CANCELLED;
    this.activatedAt = Objects.requireNonNull(now, "now");
  }

  public boolean isPending() {
    return status == ActivationTokenStatus.PENDING;
  }

  public boolean isActivated() {
    return status == ActivationTokenStatus.ACTIVATED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRoutingDecisionId() {
    return routingDecisionId;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getSourceNodeExecutionId() {
    return sourceNodeExecutionId;
  }

  public UUID getEdgeId() {
    return edgeId;
  }

  public UUID getTargetNodeDefinitionId() {
    return targetNodeDefinitionId;
  }

  public String getActivationKey() {
    return activationKey;
  }

  public String getPathToken() {
    return pathToken;
  }

  public UUID getCycleId() {
    return cycleId;
  }

  public int getIteration() {
    return iteration;
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

  public ActivationTokenStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getActivatedAt() {
    return activatedAt;
  }
}
