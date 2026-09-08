package com.fpt.workflow.file.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "files")
public class StoredFile {
  @Id private UUID id;

  @Column(name = "original_name", nullable = false, length = 512)
  private String originalName;

  @Column(name = "mime_type", nullable = false, length = 255)
  private String mimeType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(nullable = false, length = 128)
  private String checksum;

  @Column(name = "storage_provider", nullable = false, length = 64)
  private String storageProvider;

  @Column(length = 255)
  private String bucket;

  @Column(name = "storage_key", nullable = false, length = 1024)
  private String storageKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "scan_status", nullable = false, length = 32)
  private FileScanStatus scanStatus;

  @Column(name = "uploaded_by", nullable = false)
  private UUID uploadedBy;

  @Column(name = "uploaded_at", nullable = false, columnDefinition = "timestamptz")
  private Instant uploadedAt;

  @Column(name = "retention_until", columnDefinition = "timestamptz")
  private Instant retentionUntil;

  @Column(nullable = false)
  private boolean sensitive;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode metadataJson;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected StoredFile() {}

  public static StoredFile uploaded(
      UUID id,
      String originalName,
      String mimeType,
      long sizeBytes,
      String checksum,
      String storageProvider,
      String bucket,
      String storageKey,
      UUID uploadedBy,
      Instant uploadedAt,
      Instant retentionUntil,
      boolean sensitive,
      JsonNode metadataJson) {
    StoredFile value = new StoredFile();
    value.id = Objects.requireNonNull(id);
    value.originalName = text(originalName, "originalName");
    value.mimeType = text(mimeType, "mimeType");
    if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must not be negative");
    value.sizeBytes = sizeBytes;
    value.checksum = text(checksum, "checksum");
    value.storageProvider = text(storageProvider, "storageProvider");
    value.bucket = bucket == null || bucket.isBlank() ? null : bucket.trim();
    value.storageKey = text(storageKey, "storageKey");
    value.scanStatus = FileScanStatus.PENDING_SCAN;
    value.uploadedBy = Objects.requireNonNull(uploadedBy);
    value.uploadedAt = Objects.requireNonNull(uploadedAt);
    if (retentionUntil != null && retentionUntil.isBefore(uploadedAt)) {
      throw new IllegalArgumentException("retentionUntil must not precede uploadedAt");
    }
    value.retentionUntil = retentionUntil;
    value.sensitive = sensitive;
    JsonNode metadata = metadataJson == null ? JsonNodeFactory.instance.objectNode() : metadataJson;
    if (!metadata.isObject()) throw new IllegalArgumentException("metadataJson must be an object");
    value.metadataJson = metadata.deepCopy();
    return value;
  }

  public void recordScan(FileScanStatus result) {
    if (scanStatus != FileScanStatus.PENDING_SCAN) {
      throw new IllegalStateException("File scan is already terminal");
    }
    if (result == FileScanStatus.PENDING_SCAN) {
      throw new IllegalArgumentException("Scan result must be terminal");
    }
    scanStatus = Objects.requireNonNull(result);
  }

  public FileRef toRef() {
    return new FileRef(
        id,
        originalName,
        mimeType,
        sizeBytes,
        checksum,
        storageKey,
        uploadedBy,
        uploadedAt,
        scanStatus);
  }

  private static String text(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }

  public UUID getId() {
    return id;
  }

  public String getOriginalName() {
    return originalName;
  }

  public String getMimeType() {
    return mimeType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getChecksum() {
    return checksum;
  }

  public String getStorageProvider() {
    return storageProvider;
  }

  public String getBucket() {
    return bucket;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public FileScanStatus getScanStatus() {
    return scanStatus;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public Instant getUploadedAt() {
    return uploadedAt;
  }

  public Instant getRetentionUntil() {
    return retentionUntil;
  }

  public boolean isSensitive() {
    return sensitive;
  }

  public JsonNode getMetadataJson() {
    return metadataJson.deepCopy();
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
