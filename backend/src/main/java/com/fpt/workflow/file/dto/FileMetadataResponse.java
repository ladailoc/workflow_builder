package com.fpt.workflow.file.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fpt.workflow.file.domain.FileScanStatus;
import com.fpt.workflow.file.domain.StoredFile;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileMetadataResponse(
    UUID fileId,
    String originalName,
    String mimeType,
    long size,
    String checksum,
    UUID uploadedBy,
    Instant uploadedAt,
    FileScanStatus scanStatus,
    boolean sensitive) {

  public static FileMetadataResponse from(StoredFile file) {
    return new FileMetadataResponse(
        file.getId(),
        file.getOriginalName(),
        file.getMimeType(),
        file.getSizeBytes(),
        file.getChecksum(),
        file.getUploadedBy(),
        file.getUploadedAt(),
        file.getScanStatus(),
        file.isSensitive());
  }
}
