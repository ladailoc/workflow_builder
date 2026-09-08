package com.fpt.workflow.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Represents a structural unit within the organization hierarchy (department, team, etc.). */
@Entity
@Table(name = "organization_units")
public class OrganizationUnit {

  @Id private UUID id;

  @Column(name = "unit_code", nullable = false, length = 64, unique = true)
  private String unitCode;

  @Column(nullable = false, length = 256)
  private String name;

  @Column private String description;

  @Column(name = "parent_unit_id")
  private UUID parentUnitId;

  @Column(name = "manager_position_id")
  private UUID managerPositionId;

  @Column(name = "unit_type", nullable = false, length = 64)
  private String unitType;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected OrganizationUnit() {}

  private OrganizationUnit(
      UUID id,
      String unitCode,
      String name,
      String description,
      UUID parentUnitId,
      String unitType,
      Instant now) {
    if (parentUnitId != null && parentUnitId.equals(id)) {
      throw new IllegalArgumentException("OrganizationUnit cannot be its own parent");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.unitCode = requireNonBlank(unitCode, "unitCode");
    this.name = requireNonBlank(name, "name");
    this.description = description;
    this.parentUnitId = parentUnitId;
    this.managerPositionId = null;
    this.unitType = unitType == null ? "DEPARTMENT" : unitType;
    this.status = "ACTIVE";
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static OrganizationUnit create(
      UUID id,
      String unitCode,
      String name,
      String description,
      UUID parentUnitId,
      String unitType,
      Instant now) {
    return new OrganizationUnit(id, unitCode, name, description, parentUnitId, unitType, now);
  }

  public void assignManagerPosition(UUID positionId, Instant now) {
    this.managerPositionId = positionId;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void dissolve(Instant now) {
    this.status = "DISSOLVED";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public UUID getId() {
    return id;
  }

  public String getUnitCode() {
    return unitCode;
  }

  public String getName() {
    return name;
  }

  public UUID getParentUnitId() {
    return parentUnitId;
  }

  public UUID getManagerPositionId() {
    return managerPositionId;
  }

  public String getUnitType() {
    return unitType;
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
