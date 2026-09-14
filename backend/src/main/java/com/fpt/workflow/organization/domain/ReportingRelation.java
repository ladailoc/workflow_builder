package com.fpt.workflow.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Optional temporary/matrix/project/functional reporting relation (§8.5 / §25.4). Augments the
 * primary Position tree; never replaces it. A subordinate may have at most one active overriding
 * manager per priority value (enforced by the partial unique index in the schema).
 */
@Entity
@Table(name = "reporting_relations")
public class ReportingRelation {

  @Id private UUID id;

  @Column(name = "subordinate_employee_id", nullable = false)
  private UUID subordinateEmployeeId;

  @Column(name = "manager_employee_id", nullable = false)
  private UUID managerEmployeeId;

  @Column(name = "relation_type", nullable = false, length = 64)
  private String relationType;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to")
  private LocalDate effectiveTo;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected ReportingRelation() {}

  private ReportingRelation(
      UUID id,
      UUID subordinateEmployeeId,
      UUID managerEmployeeId,
      String relationType,
      int priority,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.subordinateEmployeeId =
        Objects.requireNonNull(subordinateEmployeeId, "subordinateEmployeeId");
    this.managerEmployeeId = Objects.requireNonNull(managerEmployeeId, "managerEmployeeId");
    if (subordinateEmployeeId.equals(managerEmployeeId)) {
      throw new IllegalArgumentException("A reporting relation cannot be self-referencing");
    }
    if (priority < 0) {
      throw new IllegalArgumentException("priority must not be negative");
    }
    if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
      throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
    }
    this.relationType = RelationType.normalized(Objects.requireNonNull(relationType, "relationType"));
    this.priority = priority;
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = effectiveTo;
    this.status = Status.ACTIVE.value();
    this.createdAt = now;
    this.updatedAt = now;
  }

  public static ReportingRelation create(
      UUID id,
      UUID subordinateEmployeeId,
      UUID managerEmployeeId,
      String relationType,
      int priority,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      Instant now) {
    return new ReportingRelation(
        id,
        subordinateEmployeeId,
        managerEmployeeId,
        relationType,
        priority,
        effectiveFrom,
        effectiveTo,
        now);
  }

  public void end(LocalDate on, Instant now) {
    this.status = Status.ENDED.value();
    this.effectiveTo = on;
    this.updatedAt = now;
  }

  public void deactivate(Instant now) {
    this.status = Status.INACTIVE.value();
    this.updatedAt = now;
  }

  public boolean isActiveOn(LocalDate date) {
    return Status.ACTIVE.value().equals(status)
        && !date.isBefore(effectiveFrom)
        && (effectiveTo == null || date.isBefore(effectiveTo));
  }

  public UUID getId() {
    return id;
  }

  public UUID getSubordinateEmployeeId() {
    return subordinateEmployeeId;
  }

  public UUID getManagerEmployeeId() {
    return managerEmployeeId;
  }

  public String getRelationType() {
    return relationType;
  }

  public int getPriority() {
    return priority;
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

  public enum RelationType {
    MATRIX,
    PROJECT,
    TEMPORARY,
    FUNCTIONAL;

    public String value() {
      return name();
    }

    public static String normalized(String raw) {
      try {
        return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)).value();
      } catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException(
            "Unknown reporting relation type: " + raw + ". Use one of " + java.util.Arrays.toString(values()));
      }
    }
  }

  public enum Status {
    ACTIVE,
    INACTIVE,
    ENDED;

    public String value() {
      return name();
    }
  }
}
