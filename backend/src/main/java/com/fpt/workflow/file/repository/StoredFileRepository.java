package com.fpt.workflow.file.repository;

import com.fpt.workflow.file.domain.StoredFile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

  /**
   * Overdue files for the retention executor (P2-14): retentionUntil has passed and the row is
   * not yet tombstoned. QUARANTINED/REJECTED files are intentionally excluded — disposition of
   * infected/rejected uploads follows the security policy, not retention.
   */
  @Query(
      """
      SELECT f FROM StoredFile f
      WHERE f.retentionUntil IS NOT NULL
        AND f.retentionUntil <= :now
        AND f.scanStatus IN (com.fpt.workflow.file.domain.FileScanStatus.PENDING_SCAN,
                             com.fpt.workflow.file.domain.FileScanStatus.CLEAN)
      ORDER BY f.retentionUntil ASC
      """)
  List<StoredFile> findDueForRetention(@Param("now") Instant now, Pageable pageable);

  @Query(
      """
      SELECT COUNT(f) FROM StoredFile f
      WHERE f.retentionUntil IS NOT NULL
        AND f.retentionUntil <= :now
        AND f.scanStatus IN (com.fpt.workflow.file.domain.FileScanStatus.PENDING_SCAN,
                             com.fpt.workflow.file.domain.FileScanStatus.CLEAN)
      """)
  long countDueForRetention(@Param("now") Instant now);
}
