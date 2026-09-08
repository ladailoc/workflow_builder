package com.fpt.workflow.file.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FileRef(
    UUID fileId,
    String originalName,
    String mimeType,
    long size,
    String checksum,
    String storageKey,
    UUID uploadedBy,
    Instant uploadedAt,
    FileScanStatus scanStatus) {

  public FileRef {
    Objects.requireNonNull(fileId, "fileId");
    originalName = requireText(originalName, "originalName");
    mimeType = requireText(mimeType, "mimeType");
    if (size < 0) throw new IllegalArgumentException("size must not be negative");
    checksum = requireText(checksum, "checksum");
    storageKey = requireText(storageKey, "storageKey");
    Objects.requireNonNull(uploadedBy, "uploadedBy");
    Objects.requireNonNull(uploadedAt, "uploadedAt");
    Objects.requireNonNull(scanStatus, "scanStatus");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }
}
