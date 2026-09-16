package com.fpt.workflow.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant {
  @Id private UUID id;

  @Column(name = "tenant_key", nullable = false, unique = true, length = 128)
  private String key;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected Tenant() {}

  private Tenant(UUID id, String key, String name, UUID createdBy, Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.key = requireKey(key);
    this.name = requireText(name, "name");
    this.status = "ACTIVE";
    this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static Tenant create(UUID id, String key, String name, UUID createdBy, Instant now) {
    return new Tenant(id, key, name, createdBy, now);
  }

  public UUID getId() { return id; }
  public String getKey() { return key; }
  public String getName() { return name; }
  public String getStatus() { return status; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public long getLockVersion() { return lockVersion; }

  private static String requireKey(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException("Invalid tenant key");
    }
    return value;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    return value.trim();
  }
}
