package com.fpt.workflow.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Represents an HR employee record linked to a platform user account. */
@Entity
@Table(name = "employees")
public class Employee {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false, unique = true)
  private UUID userId;

  @Column(name = "employee_code", nullable = false, length = 64, unique = true)
  private String employeeCode;

  @Column(name = "full_name", nullable = false, length = 256)
  private String fullName;

  @Column(nullable = false, length = 256)
  private String email;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected Employee() {}

  private Employee(
      UUID id, UUID userId, String employeeCode, String fullName, String email, Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.employeeCode = requireNonBlank(employeeCode, "employeeCode");
    this.fullName = requireNonBlank(fullName, "fullName");
    this.email = requireNonBlank(email, "email");
    this.status = "ACTIVE";
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static Employee create(
      UUID id, UUID userId, String employeeCode, String fullName, String email, Instant now) {
    return new Employee(id, userId, employeeCode, fullName, email, now);
  }

  public void deactivate(Instant now) {
    this.status = "INACTIVE";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void terminate(Instant now) {
    this.status = "TERMINATED";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getEmployeeCode() {
    return employeeCode;
  }

  public String getFullName() {
    return fullName;
  }

  public String getEmail() {
    return email;
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
