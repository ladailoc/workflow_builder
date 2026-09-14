package com.fpt.workflow.form.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "forms")
public class FormDefinition {
  @Id private UUID id;
  @Column(nullable = false, unique = true, length = 128) private String key;
  @Column(nullable = false, length = 200) private String name;
  @Column private String description;
  @Column(nullable = false, length = 32) private String lifecycle;
  @Column(name = "current_published_version_id") private UUID currentPublishedVersionId;
  @Column(name = "active_draft_version_id") private UUID activeDraftVersionId;
  @Column(name = "created_by", nullable = false) private UUID createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Version @Column(name = "lock_version", nullable = false) private long lockVersion;

  protected FormDefinition() {}

  public static FormDefinition create(UUID id, String key, String name, String description, UUID actor, Instant now) {
    FormDefinition value = new FormDefinition();
    value.id = id;
    value.key = FormValues.key(key);
    value.name = requireText(name, "name");
    value.description = description;
    value.lifecycle = "ACTIVE";
    value.createdBy = actor;
    value.createdAt = now;
    value.updatedAt = now;
    return value;
  }

  public void pointToDraft(UUID versionId, Instant now) { activeDraftVersionId = versionId; updatedAt = now; }
  public void pointToPublished(UUID versionId, Instant now) { currentPublishedVersionId = versionId; activeDraftVersionId = null; updatedAt = now; }
  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    return value.trim();
  }
  public UUID getId() { return id; }
  public String getKey() { return key; }
  public String getName() { return name; }
  public String getDescription() { return description; }
  public String getLifecycle() { return lifecycle; }
  public UUID getCurrentPublishedVersionId() { return currentPublishedVersionId; }
  public UUID getActiveDraftVersionId() { return activeDraftVersionId; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public long getLockVersion() { return lockVersion; }
}
