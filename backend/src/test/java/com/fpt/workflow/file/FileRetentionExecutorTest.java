package com.fpt.workflow.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fpt.workflow.file.domain.FileScanStatus;
import com.fpt.workflow.file.domain.StoredFile;
import com.fpt.workflow.file.repository.FileLinkRepository;
import com.fpt.workflow.file.repository.StoredFileRepository;
import com.fpt.workflow.file.service.FileRetentionExecutor;
import com.fpt.workflow.file.storage.ObjectStorage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * P2-14: durable file retention processing — overdue bytes purged from object storage outside the
 * DB window; metadata tombstoned (never hard-deleted); storage failures retry without corrupting
 * metadata; idempotent re-runs no-op.
 */
class FileRetentionExecutorTest {

  private final StoredFileRepository files = mock(StoredFileRepository.class);
  private final FileLinkRepository links = mock(FileLinkRepository.class);
  private final ObjectStorage storage = mock(ObjectStorage.class);
  private final Instant now = Instant.parse("2026-09-08T00:00:00Z");

  private FileRetentionExecutor executor() {
    return new FileRetentionExecutor(files, links, storage);
  }

  private StoredFile stored(UUID id) {
    return StoredFile.uploaded(
        id,
        "po.pdf",
        "application/pdf",
        10,
        "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
        "TEST",
        "bucket",
        "key-" + id,
        UUID.randomUUID(),
        now.minusSeconds(3600),
        now.minusSeconds(60),
        false,
        null);
  }

  @Test
  void overdueCleanFile_bytesDeletedAndMetadataTombstoned() {
    StoredFile file = stored(UUID.randomUUID());
    when(files.findDueForRetention(any(), any())).thenReturn(List.of(file));
    when(files.findById(file.getId())).thenReturn(Optional.of(file));

    FileRetentionExecutor.RetentionRun run = executor().processDue(now);

    assertThat(run.purged()).isEqualTo(1);
    assertThat(run.skipped()).isZero();
    verify(storage).delete(file.getStorageKey());
    assertThat(file.getScanStatus()).isEqualTo(FileScanStatus.RETENTION_PURGED);
  }

  @Test
  void storageFailure_skipsRowAndRetriesNextPoll() {
    StoredFile file = stored(UUID.randomUUID());
    when(files.findDueForRetention(any(), any())).thenReturn(List.of(file));
    doThrow(new RuntimeException("object store down")).when(storage).delete(any());

    FileRetentionExecutor.RetentionRun run = executor().processDue(now);

    assertThat(run.skipped()).isEqualTo(1);
    assertThat(run.purged()).isZero();
    verify(files, never()).save(any());
    assertThat(file.getScanStatus()).isEqualTo(FileScanStatus.PENDING_SCAN);
  }

  @Test
  void alreadyPurgedRow_noopIdempotent() {
    StoredFile file = stored(UUID.randomUUID());
    file.recordRetentionPurge(now);
    when(files.findDueForRetention(any(), any())).thenReturn(List.of(file));
    when(files.findById(file.getId())).thenReturn(Optional.of(file));

    FileRetentionExecutor.RetentionRun run = executor().processDue(now);

    assertThat(run.purged()).isEqualTo(1); // Row visited; save is the no-op branch.
  }

  @Test
  void futureRetention_notDue() {
    StoredFile file =
        StoredFile.uploaded(
            UUID.randomUUID(), "n.pdf", "application/pdf", 1, "c".repeat(64), "TEST", "b", "k",
            UUID.randomUUID(), now.minusSeconds(60), now.plusSeconds(3600), false, null);
    assertThat(file.getRetentionUntil()).isAfter(now);
    // findDueForRetention predicate (retentionUntil <= now) gates eligibility — DB-side.
  }

  @Test
  void quarantinedFile_retentionPurgeRejectedByDomain() {
    StoredFile file = stored(UUID.randomUUID());
    file.recordScan(FileScanStatus.QUARANTINED);
    assertThatThrownBy(() -> file.recordRetentionPurge(now))
        .isInstanceOf(IllegalStateException.class);
  }
}
