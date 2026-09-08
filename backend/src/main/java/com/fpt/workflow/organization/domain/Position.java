package com.fpt.workflow.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A position in the organization structure with reporting hierarchy via position_closure. */
@Entity
@Table(name = "positions")
public class Position {

  @Id private UUID id;

  @Column(name = "position_code", nullable = false, length = 64, unique = true)
  private String positionCode;

  @Column(nullable = false, length = 256)
  private String title;

  @Column(name = "org_unit_id", nullable = false)
  private UUID orgUnitId;

  @Column(name = "reports_to_position_id")
  private UUID reportsToPositionId;

  @Column(name = "is_head_of_unit", nullable = false)
  private boolean headOfUnit;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected Position() {}

  private Position(
      UUID id,
      String positionCode,
      String title,
      UUID orgUnitId,
      UUID reportsToPositionId,
      boolean headOfUnit,
      Instant now) {
    if (reportsToPositionId != null && reportsToPositionId.equals(id)) {
      throw new IllegalArgumentException("Position cannot report to itself");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.positionCode = requireNonBlank(positionCode, "positionCode");
    this.title = requireNonBlank(title, "title");
    this.orgUnitId = Objects.requireNonNull(orgUnitId, "orgUnitId");
    this.reportsToPositionId = reportsToPositionId;
    this.headOfUnit = headOfUnit;
    this.status = "ACTIVE";
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static Position create(
      UUID id,
      String positionCode,
      String title,
      UUID orgUnitId,
      UUID reportsToPositionId,
      boolean headOfUnit,
      Instant now) {
    return new Position(id, positionCode, title, orgUnitId, reportsToPositionId, headOfUnit, now);
  }

  public void abolish(Instant now) {
    this.status = "ABOLISHED";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void deactivate(Instant now) {
    this.status = "INACTIVE";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public UUID getId() {
    return id;
  }

  public String getPositionCode() {
    return positionCode;
  }

  public String getTitle() {
    return title;
  }

  public UUID getOrgUnitId() {
    return orgUnitId;
  }

  public UUID getReportsToPositionId() {
    return reportsToPositionId;
  }

  public boolean isHeadOfUnit() {
    return headOfUnit;
  }

  public String getStatus() {
    return status;
  }

  public boolean isActive() {
    return "ACTIVE".equals(status);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.strip();
  }
}
