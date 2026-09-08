package com.fpt.workflow.ticket.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
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
@Table(name = "tickets")
public class Ticket {

  @Id private UUID id;

  @Column(name = "request_type_id", nullable = false)
  private UUID requestTypeId;

  @Column(name = "creator_id", nullable = false)
  private UUID creatorId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private TicketStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "data_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode dataJson;

  @Column(name = "data_revision", nullable = false)
  private long dataRevision;

  @Column(name = "current_revision_id")
  private UUID currentRevisionId;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Column(name = "submitted_at", columnDefinition = "timestamptz")
  private Instant submittedAt;

  @Column(name = "completed_at", columnDefinition = "timestamptz")
  private Instant completedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected Ticket() {}

  private Ticket(
      UUID id, UUID requestTypeId, UUID creatorId, JsonNode dataJson, Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.requestTypeId = Objects.requireNonNull(requestTypeId, "requestTypeId");
    this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
    this.status = TicketStatus.DRAFT;
    this.dataJson = TicketValues.dataObject(dataJson, "dataJson");
    this.dataRevision = 0;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = createdAt;
  }

  public static Ticket createDraft(
      UUID id, UUID requestTypeId, UUID creatorId, JsonNode dataJson, Instant createdAt) {
    return new Ticket(id, requestTypeId, creatorId, dataJson, createdAt);
  }

  public void updateDraft(JsonNode dataJson, Instant updatedAt) {
    requireStatus(TicketStatus.DRAFT);
    this.dataJson = TicketValues.dataObject(dataJson, "dataJson");
    this.updatedAt = TicketValues.monotonicTime(updatedAt, createdAt, "updatedAt");
  }

  public long nextRevisionNo() {
    return Math.addExact(dataRevision, 1);
  }

  public void submit(UUID revisionId, long revisionNo, JsonNode dataSnapshot, Instant submittedAt) {
    TransitionGuard.requireAllowed(
        status,
        TicketStatus.SUBMITTED,
        status == TicketStatus.DRAFT ? List.of(TicketStatus.SUBMITTED) : List.of());
    advanceRevision(revisionId, revisionNo, dataSnapshot, submittedAt);
    this.status = TicketStatus.SUBMITTED;
    this.submittedAt = this.updatedAt;
  }

  public void recordBusinessRevision(
      UUID revisionId, long revisionNo, JsonNode dataSnapshot, Instant updatedAt) {
    if (status != TicketStatus.SUBMITTED && status != TicketStatus.IN_PROGRESS) {
      throw new IllegalStateException(
          "Business revision requires a submitted or in-progress Ticket");
    }
    advanceRevision(revisionId, revisionNo, dataSnapshot, updatedAt);
  }

  private void advanceRevision(
      UUID revisionId, long revisionNo, JsonNode dataSnapshot, Instant timestamp) {
    long expectedRevision = nextRevisionNo();
    if (revisionNo != expectedRevision) {
      throw new IllegalArgumentException(
          "revisionNo must be the next Ticket revision: " + expectedRevision);
    }
    this.currentRevisionId = Objects.requireNonNull(revisionId, "revisionId");
    this.dataJson = TicketValues.dataObject(dataSnapshot, "dataSnapshot");
    this.dataRevision = revisionNo;
    this.updatedAt = TicketValues.monotonicTime(timestamp, createdAt, "updatedAt");
  }

  private void requireStatus(TicketStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("Ticket must be " + expected.name());
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getRequestTypeId() {
    return requestTypeId;
  }

  public UUID getCreatorId() {
    return creatorId;
  }

  public TicketStatus getStatus() {
    return status;
  }

  public JsonNode getDataJson() {
    return dataJson.deepCopy();
  }

  public long getDataRevision() {
    return dataRevision;
  }

  public UUID getCurrentRevisionId() {
    return currentRevisionId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
