package com.fpt.workflow.operations.retention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of an executed (or previewed-then-executed) retention decision (P2-15,
 * backed by the {@code retention_actions} table). Rows are append-only evidence: the executor
 * never updates or deletes them.
 */
@Entity
@Table(name = "retention_actions")
public class RetentionAction {

  @Id private UUID id;

  @Column(name = "policy_id")
  private UUID policyId;

  @Column(nullable = false, length = 32)
  private String category;

  @Column(name = "aggregate_type", nullable = false, length = 128)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(nullable = false, length = 32)
  private String action;

  @Column(nullable = false, columnDefinition = "text")
  private String reason;

  @Column(name = "decided_at", nullable = false, columnDefinition = "timestamptz")
  private Instant decidedAt;

  protected RetentionAction() {}

  private RetentionAction(
      UUID id,
      UUID policyId,
      String category,
      String aggregateType,
      UUID aggregateId,
      String action,
      String reason,
      Instant decidedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.policyId = policyId;
    this.category = normalize(category, "category");
    this.aggregateType = normalize(aggregateType, "aggregateType");
    this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
    this.action = normalize(action, "action");
    this.reason =
        Objects.requireNonNull(reason, "reason") == null || reason.isBlank()
            ? reason
            : reason.strip();
    this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
  }

  public static RetentionAction decide(
      UUID id,
      UUID policyId,
      String category,
      String aggregateType,
      UUID aggregateId,
      String action,
      String reason,
      Instant decidedAt) {
    return new RetentionAction(
        id, policyId, category, aggregateType, aggregateId, action, reason, decidedAt);
  }

  private static String normalize(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    return value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  public UUID getId() {
    return id;
  }

  public UUID getPolicyId() {
    return policyId;
  }

  public String getCategory() {
    return category;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getAction() {
    return action;
  }

  public String getReason() {
    return reason;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }
}
