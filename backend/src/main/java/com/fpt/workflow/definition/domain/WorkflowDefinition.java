package com.fpt.workflow.definition.domain;

import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinition {

  @Id private UUID id;

  @Column(name = "key", nullable = false, length = 128)
  private String key;

  @Column(nullable = false, length = 200)
  private String name;

  @Column private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private WorkflowDefinitionLifecycle lifecycle;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "current_published_version_id")
  private UUID currentPublishedVersionId;

  @Column(name = "active_draft_version_id")
  private UUID activeDraftVersionId;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected WorkflowDefinition() {}

  private WorkflowDefinition(
      UUID id,
      String key,
      String name,
      String description,
      UUID ownerId,
      UUID createdBy,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.key = DefinitionValues.key(key);
    this.name = DefinitionValues.requiredText(name, "name");
    this.description = description;
    this.lifecycle = WorkflowDefinitionLifecycle.ACTIVE;
    this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
    this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = createdAt;
  }

  public static WorkflowDefinition create(
      UUID id,
      String key,
      String name,
      String description,
      UUID ownerId,
      UUID createdBy,
      Instant createdAt) {
    return new WorkflowDefinition(id, key, name, description, ownerId, createdBy, createdAt);
  }

  public void updateDetails(
      String name,
      String description,
      UUID ownerId,
      WorkflowDefinitionLifecycle lifecycle,
      Instant updatedAt) {
    this.name = DefinitionValues.requiredText(name, "name");
    this.description = description;
    this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    touch(updatedAt);
  }

  public void assignActiveDraft(UUID workflowVersionId, Instant updatedAt) {
    if (activeDraftVersionId != null && !activeDraftVersionId.equals(workflowVersionId)) {
      throw new IllegalStateException("Workflow definition already has an active draft");
    }
    activeDraftVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    touch(updatedAt);
  }

  public void clearActiveDraft(UUID workflowVersionId, Instant updatedAt) {
    if (!Objects.equals(activeDraftVersionId, workflowVersionId)) {
      throw new IllegalStateException("Workflow version is not the active draft");
    }
    activeDraftVersionId = null;
    touch(updatedAt);
  }

  public void publishVersion(UUID workflowVersionId, Instant updatedAt) {
    if (!Objects.equals(activeDraftVersionId, workflowVersionId)) {
      throw new IllegalStateException("Only the active draft can become current Published");
    }
    currentPublishedVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    activeDraftVersionId = null;
    touch(updatedAt);
  }

  private void touch(Instant updatedAt) {
    Instant nextUpdatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (nextUpdatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
    this.updatedAt = nextUpdatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getKey() {
    return key;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public WorkflowDefinitionLifecycle getLifecycle() {
    return lifecycle;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getCurrentPublishedVersionId() {
    return currentPublishedVersionId;
  }

  public UUID getActiveDraftVersionId() {
    return activeDraftVersionId;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
