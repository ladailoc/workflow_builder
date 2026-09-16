package com.fpt.workflow.ticketcategory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A tenant-specific workflow selection for a category. A null tenantId is the default scope. */
@Entity
@Table(name = "ticket_category_workflow_bindings")
public class TicketCategoryWorkflowBinding {
  @Id private UUID id;
  @Column(name = "ticket_category_id", nullable = false) private UUID ticketCategoryId;
  @Column(name = "tenant_id") private UUID tenantId;
  @Column(name = "category_version_id", nullable = false) private UUID categoryVersionId;
  @Column(name = "workflow_version_id", nullable = false) private UUID workflowVersionId;
  @Column(name = "created_by", nullable = false) private UUID createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Version @Column(name = "lock_version", nullable = false) private long lockVersion;

  protected TicketCategoryWorkflowBinding() {}

  private TicketCategoryWorkflowBinding(
      UUID id,
      UUID ticketCategoryId,
      UUID tenantId,
      UUID categoryVersionId,
      UUID workflowVersionId,
      UUID createdBy,
      Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.ticketCategoryId = Objects.requireNonNull(ticketCategoryId, "ticketCategoryId");
    this.tenantId = tenantId;
    this.categoryVersionId = Objects.requireNonNull(categoryVersionId, "categoryVersionId");
    this.workflowVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static TicketCategoryWorkflowBinding create(
      UUID id,
      UUID categoryId,
      UUID tenantId,
      UUID categoryVersionId,
      UUID workflowVersionId,
      UUID actorId,
      Instant now) {
    return new TicketCategoryWorkflowBinding(
        id, categoryId, tenantId, categoryVersionId, workflowVersionId, actorId, now);
  }

  public void update(
      long expectedLockVersion,
      UUID categoryVersionId,
      UUID workflowVersionId,
      Instant now) {
    if (lockVersion != expectedLockVersion) throw new IllegalStateException("STALE_TENANT_BINDING");
    this.categoryVersionId = Objects.requireNonNull(categoryVersionId, "categoryVersionId");
    this.workflowVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void rebaseCategoryVersion(UUID categoryVersionId, Instant now) {
    this.categoryVersionId = Objects.requireNonNull(categoryVersionId, "categoryVersionId");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void syncToPublished(UUID categoryVersionId, UUID workflowVersionId, Instant now) {
    this.categoryVersionId = Objects.requireNonNull(categoryVersionId, "categoryVersionId");
    this.workflowVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public UUID getId() { return id; }
  public UUID getTicketCategoryId() { return ticketCategoryId; }
  public UUID getTenantId() { return tenantId; }
  public UUID getCategoryVersionId() { return categoryVersionId; }
  public UUID getWorkflowVersionId() { return workflowVersionId; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public long getLockVersion() { return lockVersion; }
  public boolean isDefault() { return tenantId == null; }
}
