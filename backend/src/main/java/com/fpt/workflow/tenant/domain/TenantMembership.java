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
@Table(name = "tenant_memberships")
public class TenantMembership {
  @Id private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(nullable = false, length = 32) private String role;
  @Column(nullable = false, length = 32) private String status;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Version @Column(name = "lock_version", nullable = false) private long lockVersion;

  protected TenantMembership() {}

  private TenantMembership(UUID id, UUID tenantId, UUID userId, String role, Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.role = normalizeRole(role);
    this.status = "ACTIVE";
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static TenantMembership create(UUID id, UUID tenantId, UUID userId, String role, Instant now) {
    return new TenantMembership(id, tenantId, userId, role, now);
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getUserId() { return userId; }
  public String getRole() { return role; }
  public String getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public long getLockVersion() { return lockVersion; }

  public boolean isActive() { return "ACTIVE".equals(status); }
  public boolean isTenantAdmin() { return isActive() && "TENANT_ADMIN".equals(role); }

  private static String normalizeRole(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("membership role is required");
    String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!normalized.equals("TENANT_ADMIN") && !normalized.equals("TENANT_MEMBER")) {
      throw new IllegalArgumentException("Invalid membership role");
    }
    return normalized;
  }
}
