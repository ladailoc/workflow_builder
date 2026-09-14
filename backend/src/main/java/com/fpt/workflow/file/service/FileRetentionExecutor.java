package com.fpt.workflow.file.service;

import com.fpt.workflow.file.domain.FileScanStatus;
import com.fpt.workflow.file.domain.StoredFile;
import com.fpt.workflow.file.repository.FileLinkRepository;
import com.fpt.workflow.file.repository.StoredFileRepository;
import com.fpt.workflow.file.storage.ObjectStorage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable file retention executor (P2-14 §18.2/§29.2). Files whose retentionUntil has passed are
 * tombstoned: object-storage bytes are deleted outside the DB transaction window, then the DB row
 * transitions to PURGED so audit/timeline history stays intact (no hard delete of metadata).
 *
 * <p>Guards: only non-retained files beyond retentionUntil are processed; sensitive files keep
 * their bytes purged but never have the row removed; idempotent re-runs find nothing to do.
 */
@Service
public class FileRetentionExecutor {

  /** Maximum number of files processed per poll batch (bounded durable job work). */
  private static final int BATCH_SIZE = 100;

  private final StoredFileRepository storedFiles;
  private final FileLinkRepository fileLinks;
  private final ObjectStorage objectStorage;

  @Autowired
  public FileRetentionExecutor(
      StoredFileRepository storedFiles, FileLinkRepository fileLinks, ObjectStorage objectStorage) {
    this.storedFiles = storedFiles;
    this.fileLinks = fileLinks;
    this.objectStorage = objectStorage;
  }

  public FileRetentionExecutor(
      StoredFileRepository storedFiles, FileLinkRepository fileLinks) {
    this(storedFiles, fileLinks, null);
  }

  /**
   * Processes one bounded batch of overdue files. Object-storage deletion happens before the
   * transactional purge bookkeeping so DB transactions never wait on external I/O; a storage
   * failure skips the row (retried by the next poll) instead of corrupting the metadata.
   */
  public RetentionRun processDue(Instant now) {
    List<StoredFile> overdue =
        storedFiles.findDueForRetention(now, org.springframework.data.domain.PageRequest.of(0, BATCH_SIZE));
    int purged = 0;
    int skipped = 0;
    for (StoredFile file : overdue) {
      if (objectStorage != null) {
        try {
          objectStorage.delete(file.getStorageKey());
        } catch (RuntimeException storageFailure) {
          skipped++;
          continue; // Storage failure → retry next poll; metadata untouched.
        }
      }
      purgeMetadata(file, now);
      purged++;
    }
    return new RetentionRun(overdue.size(), purged, skipped, now);
  }

  @Transactional
  protected void purgeMetadata(StoredFile file, Instant now) {
    StoredFile reloaded = storedFiles.findById(file.getId()).orElse(null);
    if (reloaded == null || reloaded.getScanStatus() == FileScanStatus.RETENTION_PURGED) {
      return; // Idempotent: already purged by a concurrent worker.
    }
    reloaded.recordRetentionPurge(now);
    storedFiles.save(reloaded);
  }

  /** Preview/dry-run support: returns how many files are due without purging them. */
  public RetentionPreview previewDue(Instant now) {
    return new RetentionPreview(storedFiles.countDueForRetention(now));
  }

  public record RetentionRun(int examined, int purged, int skipped, Instant executedAt) {}

  public record RetentionPreview(long dueCount) {}

  public interface RetentionStatisticsPort {
    Optional<UUID> lastPurgedFileId();
  }
}
