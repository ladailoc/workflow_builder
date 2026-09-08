package com.fpt.workflow.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Binds an employee to a position with effective dating, primary flag and status. UNIQUE
 * (employee_id) WHERE is_primary=TRUE AND status='ACTIVE' enforced in DB.
 */
@Entity
@Table(name = "position_assignments")
public class PositionAssignment {

  @Id private UUID id;

  @Column(name = "employee_id", nullable = false)
  private UUID employeeId;

  @Column(name = "position_id", nullable = false)
  private UUID positionId;

  @Column(name = "is_primary", nullable = false)
  private boolean primary;

  @Column(name = "assignment_type", nullable = false, length = 64)
  private String assignmentType;

  @Column(name = "effective_from", nullable = false, columnDefinition = "date")
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", columnDefinition = "date")
  private LocalDate effectiveTo;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected PositionAssignment() {}

  private PositionAssignment(
      UUID id,
      UUID employeeId,
      UUID positionId,
      boolean primary,
      String assignmentType,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      Instant now) {
    if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
      throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.positionId = Objects.requireNonNull(positionId, "positionId");
    this.primary = primary;
    this.assignmentType = assignmentType == null ? "PERMANENT" : assignmentType;
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = effectiveTo;
    this.status = "ACTIVE";
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static PositionAssignment create(
      UUID id,
      UUID employeeId,
      UUID positionId,
      boolean primary,
      String assignmentType,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      Instant now) {
    return new PositionAssignment(
        id, employeeId, positionId, primary, assignmentType, effectiveFrom, effectiveTo, now);
  }

  public void end(LocalDate endDate, Instant now) {
    this.effectiveTo = Objects.requireNonNull(endDate, "endDate");
    this.status = "ENDED";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void deactivate(Instant now) {
    this.status = "INACTIVE";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  /** Returns true if this assignment is currently active on the given reference date. */
  public boolean isEffectiveOn(LocalDate referenceDate) {
    if (!"ACTIVE".equals(status)) return false;
    if (referenceDate.isBefore(effectiveFrom)) return false;
    return effectiveTo == null || !referenceDate.isAfter(effectiveTo);
  }

  public UUID getId() {
    return id;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public UUID getPositionId() {
    return positionId;
  }

  public boolean isPrimary() {
    return primary;
  }

  public String getAssignmentType() {
    return assignmentType;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public String getStatus() {
    return status;
  }
}
