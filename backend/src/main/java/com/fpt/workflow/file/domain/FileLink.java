package com.fpt.workflow.file.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "file_links")
public class FileLink {
  @Id private UUID id;

  @Column(name = "file_id", nullable = false)
  private UUID fileId;

  @Enumerated(EnumType.STRING)
  @Column(name = "owner_type", nullable = false, length = 32)
  private FileLinkOwnerType ownerType;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "field_key", nullable = false, length = 128)
  private String fieldKey;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  protected FileLink() {}

  public static FileLink create(
      UUID id,
      UUID fileId,
      FileLinkOwnerType ownerType,
      UUID ownerId,
      String fieldKey,
      Instant createdAt) {
    FileLink value = new FileLink();
    value.id = Objects.requireNonNull(id);
    value.fileId = Objects.requireNonNull(fileId);
    value.ownerType = Objects.requireNonNull(ownerType);
    value.ownerId = Objects.requireNonNull(ownerId);
    if (fieldKey == null || fieldKey.isBlank())
      throw new IllegalArgumentException("fieldKey is required");
    value.fieldKey = fieldKey.trim();
    value.createdAt = Objects.requireNonNull(createdAt);
    return value;
  }

  public UUID getId() {
    return id;
  }

  public UUID getFileId() {
    return fileId;
  }

  public FileLinkOwnerType getOwnerType() {
    return ownerType;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getFieldKey() {
    return fieldKey;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
