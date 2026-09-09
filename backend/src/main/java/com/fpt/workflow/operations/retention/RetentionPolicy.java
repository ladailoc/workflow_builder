package com.fpt.workflow.operations.retention;

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

@Entity
@Table(name = "retention_policies")
public class RetentionPolicy {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private RetentionCategory category;

  @Column(name = "retention_days", nullable = false)
  private int retentionDays;

  @Enumerated(EnumType.STRING)
  @Column(name = "expiry_action", nullable = false, length = 32)
  private RetentionExpiryAction expiryAction;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected RetentionPolicy() {}

  private RetentionPolicy(
      UUID id,
      RetentionCategory category,
      int retentionDays,
      RetentionExpiryAction expiryAction,
      boolean enabled,
      Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.category = Objects.requireNonNull(category, "category");
    this.createdAt = Objects.requireNonNull(now, "now");
    update(retentionDays, expiryAction, enabled, now);
  }

  public static RetentionPolicy create(
      UUID id,
      RetentionCategory category,
      int retentionDays,
      RetentionExpiryAction expiryAction,
      boolean enabled,
      Instant now) {
    return new RetentionPolicy(id, category, retentionDays, expiryAction, enabled, now);
  }

  public void update(
      int retentionDays, RetentionExpiryAction expiryAction, boolean enabled, Instant now) {
    if (retentionDays < 1) {
      throw new IllegalArgumentException("retentionDays must be positive");
    }
    this.retentionDays = retentionDays;
    this.expiryAction = Objects.requireNonNull(expiryAction, "expiryAction");
    this.enabled = enabled;
    this.updatedAt = Objects.requireNonNull(now, "now");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  public UUID getId() {
    return id;
  }

  public RetentionCategory getCategory() {
    return category;
  }

  public int getRetentionDays() {
    return retentionDays;
  }

  public RetentionExpiryAction getExpiryAction() {
    return expiryAction;
  }

  public boolean isEnabled() {
    return enabled;
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
