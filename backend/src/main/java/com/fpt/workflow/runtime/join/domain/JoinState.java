package com.fpt.workflow.runtime.join.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tracks the durable join synchronization state for a specific (event_id, node_definition_id,
 * join_scope_id).
 */
@Entity
@Table(name = "join_states")
public class JoinState {

  @Id private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "node_definition_id", nullable = false)
  private UUID nodeDefinitionId;

  @Column(name = "join_scope_id", nullable = false)
  private UUID joinScopeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "join_policy", nullable = false, length = 32)
  private JoinPolicy joinPolicy;

  @Column(name = "required_count", nullable = false)
  private int requiredCount;

  @Column(name = "arrived_count", nullable = false)
  private int arrivedCount;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "join_node_execution_id")
  private UUID joinNodeExecutionId;

  @Column(name = "routed_downstream", nullable = false)
  private boolean routedDownstream;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected JoinState() {}

  public static JoinState create(
      UUID id,
      UUID eventId,
      UUID nodeDefinitionId,
      UUID joinScopeId,
      JoinPolicy joinPolicy,
      int requiredCount,
      UUID joinNodeExecutionId,
      Instant now) {
    JoinState state = new JoinState();
    state.id = Objects.requireNonNull(id, "id");
    state.eventId = Objects.requireNonNull(eventId, "eventId");
    state.nodeDefinitionId = Objects.requireNonNull(nodeDefinitionId, "nodeDefinitionId");
    state.joinScopeId = Objects.requireNonNull(joinScopeId, "joinScopeId");
    state.joinPolicy = Objects.requireNonNull(joinPolicy, "joinPolicy");
    state.requiredCount = Math.max(1, requiredCount);
    state.arrivedCount = 0;
    state.status = "WAITING";
    state.joinNodeExecutionId = joinNodeExecutionId;
    state.routedDownstream = false;
    state.createdAt = Objects.requireNonNull(now, "now");
    state.updatedAt = now;
    return state;
  }

  /**
   * Records the arrival of an inbound branch.
   *
   * @return true if the join completion condition is reached.
   */
  public boolean recordArrival(Instant now) {
    this.arrivedCount++;
    this.updatedAt = now;
    return switch (joinPolicy) {
      case AND -> arrivedCount >= requiredCount;
      case FIRST -> arrivedCount >= 1;
      case N_OF_M -> arrivedCount >= requiredCount;
    };
  }

  /**
   * Atomically claims downstream routing right.
   *
   * @return true if this call successfully claimed routing, false if already claimed.
   */
  public boolean markRoutedDownstream() {
    if (routedDownstream) return false;
    this.routedDownstream = true;
    return true;
  }

  public void markCompleted(Instant now) {
    this.status = "COMPLETED";
    this.updatedAt = now;
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

  public UUID getJoinScopeId() {
    return joinScopeId;
  }

  public JoinPolicy getJoinPolicy() {
    return joinPolicy;
  }

  public int getRequiredCount() {
    return requiredCount;
  }

  public int getArrivedCount() {
    return arrivedCount;
  }

  public String getStatus() {
    return status;
  }

  public UUID getJoinNodeExecutionId() {
    return joinNodeExecutionId;
  }

  public void setJoinNodeExecutionId(UUID joinNodeExecutionId) {
    this.joinNodeExecutionId = joinNodeExecutionId;
  }

  public boolean isRoutedDownstream() {
    return routedDownstream;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
