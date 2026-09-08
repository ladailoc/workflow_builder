package com.fpt.workflow.ticket.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ticket_subjects")
public class TicketSubject {

  @Id private UUID id;

  @Column(name = "ticket_id", nullable = false)
  private UUID ticketId;

  @Column(name = "subject_type", nullable = false, length = 128)
  private String subjectType;

  @Column(name = "subject_ref_id", nullable = false)
  private UUID subjectRefId;

  @Column(name = "role_key", nullable = false, length = 128)
  private String roleKey;

  @Column(name = "source_field", length = 256)
  private String sourceField;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  protected TicketSubject() {}

  private TicketSubject(
      UUID id,
      UUID ticketId,
      String subjectType,
      UUID subjectRefId,
      String roleKey,
      String sourceField,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.subjectType = TicketValues.key(subjectType, "subjectType");
    this.subjectRefId = Objects.requireNonNull(subjectRefId, "subjectRefId");
    this.roleKey = TicketValues.key(roleKey, "roleKey");
    this.sourceField = TicketValues.sourceField(sourceField);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public static TicketSubject create(
      UUID id,
      UUID ticketId,
      String subjectType,
      UUID subjectRefId,
      String roleKey,
      String sourceField,
      Instant createdAt) {
    return new TicketSubject(
        id, ticketId, subjectType, subjectRefId, roleKey, sourceField, createdAt);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public String getSubjectType() {
    return subjectType;
  }

  public UUID getSubjectRefId() {
    return subjectRefId;
  }

  public String getRoleKey() {
    return roleKey;
  }

  public String getSourceField() {
    return sourceField;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
