package com.fpt.workflow.rework.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "revision_requests")
public class RevisionRequest {
  @Id private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "source_task_id", nullable = false)
  private UUID sourceTaskId;

  @Column(name = "requested_by", nullable = false)
  private UUID requestedBy;

  @Column(name = "target_node_id", nullable = false)
  private UUID targetNodeId;

  @Column(name = "cycle_id", nullable = false)
  private UUID cycleId;

  @Column private String comment;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private RevisionRequestStatus status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "completed_at", columnDefinition = "timestamptz")
  private Instant completedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected RevisionRequest() {}

  public static RevisionRequest open(
      UUID id,
      UUID eventId,
      UUID sourceTaskId,
      UUID requestedBy,
      UUID targetNodeId,
      UUID cycleId,
      String comment,
      Instant createdAt) {
    RevisionRequest value = new RevisionRequest();
    value.id = Objects.requireNonNull(id);
    value.eventId = Objects.requireNonNull(eventId);
    value.sourceTaskId = Objects.requireNonNull(sourceTaskId);
    value.requestedBy = Objects.requireNonNull(requestedBy);
    value.targetNodeId = Objects.requireNonNull(targetNodeId);
    value.cycleId = Objects.requireNonNull(cycleId);
    value.comment = comment == null || comment.isBlank() ? null : comment.trim();
    value.status = RevisionRequestStatus.OPEN;
    value.createdAt = Objects.requireNonNull(createdAt);
    return value;
  }

  public void submit(Instant at) {
    if (status != RevisionRequestStatus.OPEN)
      throw new IllegalStateException("Revision request is terminal");
    status = RevisionRequestStatus.SUBMITTED;
    completedAt = Objects.requireNonNull(at);
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getSourceTaskId() {
    return sourceTaskId;
  }

  public UUID getRequestedBy() {
    return requestedBy;
  }

  public UUID getTargetNodeId() {
    return targetNodeId;
  }

  public UUID getCycleId() {
    return cycleId;
  }

  public String getComment() {
    return comment;
  }

  public RevisionRequestStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
