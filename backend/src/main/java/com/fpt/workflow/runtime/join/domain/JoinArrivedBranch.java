package com.fpt.workflow.runtime.join.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of an inbound branch arrival at a JoinState. Arrival identity follows the
 * logical path token (§25.14): rework/retry of the same branch does not satisfy the join twice;
 * a genuinely new path token (new cycle/iteration) counts as a new arrival. Legacy rows without
 * a path token keep the execution-id based uniqueness.
 */
@Entity
@Table(name = "join_arrived_branches")
public class JoinArrivedBranch {

  @Id private UUID id;

  @Column(name = "join_state_id", nullable = false)
  private UUID joinStateId;

  @Column(name = "inbound_execution_id", nullable = false)
  private UUID inboundExecutionId;

  @Column(name = "inbound_path_token", length = 256)
  private String inboundPathToken;

  @Column(name = "inbound_cycle_id")
  private UUID inboundCycleId;

  @Column(name = "inbound_edge_id", nullable = false)
  private UUID inboundEdgeId;

  @Column(name = "arrived_at", nullable = false, columnDefinition = "timestamptz")
  private Instant arrivedAt;

  protected JoinArrivedBranch() {}

  public static JoinArrivedBranch create(
      UUID id,
      UUID joinStateId,
      UUID inboundExecutionId,
      UUID inboundEdgeId,
      Instant arrivedAt) {
    return create(id, joinStateId, inboundExecutionId, null, null, inboundEdgeId, arrivedAt);
  }

  public static JoinArrivedBranch create(
      UUID id,
      UUID joinStateId,
      UUID inboundExecutionId,
      String inboundPathToken,
      UUID inboundCycleId,
      UUID inboundEdgeId,
      Instant arrivedAt) {
    JoinArrivedBranch branch = new JoinArrivedBranch();
    branch.id = Objects.requireNonNull(id, "id");
    branch.joinStateId = Objects.requireNonNull(joinStateId, "joinStateId");
    branch.inboundExecutionId = Objects.requireNonNull(inboundExecutionId, "inboundExecutionId");
    branch.inboundPathToken =
        inboundPathToken == null || inboundPathToken.isBlank() ? null : inboundPathToken;
    branch.inboundCycleId = inboundCycleId;
    branch.inboundEdgeId = Objects.requireNonNull(inboundEdgeId, "inboundEdgeId");
    branch.arrivedAt = Objects.requireNonNull(arrivedAt, "arrivedAt");
    return branch;
  }

  public UUID getId() {
    return id;
  }

  public UUID getJoinStateId() {
    return joinStateId;
  }

  public UUID getInboundExecutionId() {
    return inboundExecutionId;
  }

  public String getInboundPathToken() {
    return inboundPathToken;
  }

  public UUID getInboundCycleId() {
    return inboundCycleId;
  }

  public UUID getInboundEdgeId() {
    return inboundEdgeId;
  }

  public Instant getArrivedAt() {
    return arrivedAt;
  }
}
