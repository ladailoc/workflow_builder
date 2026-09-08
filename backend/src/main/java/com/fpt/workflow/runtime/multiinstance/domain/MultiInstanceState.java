package com.fpt.workflow.runtime.multiinstance.domain;

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
 * Aggregate state for a multi-instance node execution. Tracks completion counts, policy, and
 * ensures downstream is only routed once.
 */
@Entity
@Table(name = "multi_instance_states")
public class MultiInstanceState {

  @Id private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "node_execution_id", nullable = false, unique = true)
  private UUID nodeExecutionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "execution_mode", nullable = false, length = 32)
  private ExecutionMode executionMode;

  @Column(name = "total_items", nullable = false)
  private int totalItems;

  @Column(name = "completed_items", nullable = false)
  private int completedItems;

  @Column(name = "failed_items", nullable = false)
  private int failedItems;

  @Column(name = "cancelled_items", nullable = false)
  private int cancelledItems;

  @Enumerated(EnumType.STRING)
  @Column(name = "completion_policy", nullable = false, length = 32)
  private CompletionPolicy completionPolicy;

  @Column(name = "completion_threshold")
  private Integer completionThreshold;

  @Enumerated(EnumType.STRING)
  @Column(name = "remaining_item_policy", nullable = false, length = 32)
  private RemainingItemPolicy remainingItemPolicy;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "routed_downstream", nullable = false)
  private boolean routedDownstream;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected MultiInstanceState() {}

  private MultiInstanceState(
      UUID id,
      UUID eventId,
      UUID nodeExecutionId,
      ExecutionMode executionMode,
      int totalItems,
      CompletionPolicy completionPolicy,
      Integer completionThreshold,
      RemainingItemPolicy remainingItemPolicy,
      Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.nodeExecutionId = Objects.requireNonNull(nodeExecutionId, "nodeExecutionId");
    this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
    if (totalItems <= 0) {
      throw new IllegalArgumentException("totalItems must be > 0");
    }
    this.totalItems = totalItems;
    this.completedItems = 0;
    this.failedItems = 0;
    this.cancelledItems = 0;
    this.completionPolicy = Objects.requireNonNull(completionPolicy, "completionPolicy");
    this.completionThreshold = completionThreshold;
    this.remainingItemPolicy = Objects.requireNonNull(remainingItemPolicy, "remainingItemPolicy");
    this.status = "RUNNING";
    this.routedDownstream = false;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static MultiInstanceState create(
      UUID id,
      UUID eventId,
      UUID nodeExecutionId,
      ExecutionMode executionMode,
      int totalItems,
      CompletionPolicy completionPolicy,
      Integer completionThreshold,
      RemainingItemPolicy remainingItemPolicy,
      Instant now) {
    return new MultiInstanceState(
        id,
        eventId,
        nodeExecutionId,
        executionMode,
        totalItems,
        completionPolicy,
        completionThreshold,
        remainingItemPolicy,
        now);
  }

  public synchronized boolean recordItemCompletion(Instant now) {
    this.completedItems++;
    this.updatedAt = Objects.requireNonNull(now, "now");
    return checkThresholdSatisfied();
  }

  public synchronized void recordItemFailure(Instant now) {
    this.failedItems++;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public synchronized void recordItemCancelled(Instant now) {
    this.cancelledItems++;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public boolean checkThresholdSatisfied() {
    return switch (completionPolicy) {
      case ALL -> completedItems >= totalItems;
      case ANY -> completedItems >= 1;
      case N_OF_M -> completionThreshold != null && completedItems >= completionThreshold;
      case PERCENTAGE ->
          completionThreshold != null
              && ((double) completedItems / totalItems) * 100.0 >= completionThreshold;
    };
  }

  public void markCompleted(Instant now) {
    this.status = "COMPLETED";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void markFailed(Instant now) {
    this.status = "FAILED";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public boolean markRoutedDownstream() {
    if (routedDownstream) {
      return false; // already routed!
    }
    this.routedDownstream = true;
    return true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getNodeExecutionId() {
    return nodeExecutionId;
  }

  public ExecutionMode getExecutionMode() {
    return executionMode;
  }

  public int getTotalItems() {
    return totalItems;
  }

  public int getCompletedItems() {
    return completedItems;
  }

  public int getFailedItems() {
    return failedItems;
  }

  public int getCancelledItems() {
    return cancelledItems;
  }

  public CompletionPolicy getCompletionPolicy() {
    return completionPolicy;
  }

  public Integer getCompletionThreshold() {
    return completionThreshold;
  }

  public RemainingItemPolicy getRemainingItemPolicy() {
    return remainingItemPolicy;
  }

  public String getStatus() {
    return status;
  }

  public boolean isRoutedDownstream() {
    return routedDownstream;
  }
}
