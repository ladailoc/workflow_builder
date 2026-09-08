package com.fpt.workflow.file.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.file.domain.*;
import com.fpt.workflow.file.repository.*;
import com.fpt.workflow.file.storage.StoredObject;
import com.fpt.workflow.security.ActorContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database-only transactions for file metadata. Object storage is never called here. */
@Service
public class FileMetadataTransactions {
  private final StoredFileRepository files;
  private final FileLinkRepository links;
  private final FileDownloadAuthorizer authorizer;

  public FileMetadataTransactions(
      StoredFileRepository files, FileLinkRepository links, FileDownloadAuthorizer authorizer) {
    this.files = files;
    this.links = links;
    this.authorizer = authorizer;
  }

  @Transactional(readOnly = true)
  public void verifyCapacity(FileUpload upload, int maximum) {
    long current =
        links.countByOwnerTypeAndOwnerIdAndFieldKey(
            upload.ownerType(), upload.ownerId(), upload.fieldKey());
    if (current >= maximum) throw new IllegalArgumentException("File count limit exceeded");
  }

  @Transactional
  public FileRef persistUpload(
      UUID fileId,
      UUID linkId,
      FileUpload upload,
      FilePolicy policy,
      byte[] bytes,
      String checksum,
      String provider,
      StoredObject object,
      UUID actorId,
      Instant now,
      JsonNode metadata) {
    verifyCapacity(upload, policy.maxFileCount());
    StoredFile file =
        StoredFile.uploaded(
            fileId,
            upload.originalName(),
            upload.mimeType(),
            bytes.length,
            checksum,
            provider,
            object.bucket(),
            object.storageKey(),
            actorId,
            now,
            policy.retention() == null ? null : now.plus(policy.retention()),
            policy.sensitive(),
            metadata);
    file = files.saveAndFlush(file);
    links.saveAndFlush(
        FileLink.create(
            linkId, fileId, upload.ownerType(), upload.ownerId(), upload.fieldKey(), now));
    return file.toRef();
  }

  @Transactional(readOnly = true)
  public String authorizeDownload(UUID fileId, ActorContext actor) {
    StoredFile file =
        files.findById(fileId).orElseThrow(() -> new IllegalArgumentException("File not found"));
    if (file.getScanStatus() != FileScanStatus.CLEAN)
      throw new AccessDeniedException("File is not available for download");
    if (!authorizer.mayDownload(actor, file, links.findAllByFileId(fileId)))
      throw new AccessDeniedException("File download is forbidden");
    return file.getStorageKey();
  }

  @Transactional
  public FileRef recordScan(UUID fileId, FileScanStatus result) {
    StoredFile file =
        files.findById(fileId).orElseThrow(() -> new IllegalArgumentException("File not found"));
    file.recordScan(result);
    return files.saveAndFlush(file).toRef();
  }
}
