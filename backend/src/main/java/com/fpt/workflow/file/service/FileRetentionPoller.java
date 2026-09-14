package com.fpt.workflow.file.service;

import com.fpt.workflow.shared.time.PlatformClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Durable file retention trigger (P2-14). The DB is the source of truth: each poll batch claims
 * overdue files by {@code (retention_until, scan_status)} predicate; object-storage deletion
 * happens outside transactions inside {@link FileRetentionExecutor}, and lease/purge contention
 * is idempotent via the RETENTION_PURGED tombstone. Safe under multiple instances.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
    name = "platform.file.retention.poller.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FileRetentionPoller {

  private static final Logger log = LoggerFactory.getLogger(FileRetentionPoller.class);

  private final FileRetentionExecutor retentionExecutor;
  private final PlatformClock clock;

  public FileRetentionPoller(FileRetentionExecutor retentionExecutor, PlatformClock clock) {
    this.retentionExecutor = retentionExecutor;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${platform.file.retention.poller.fixed-delay-ms:300000}")
  public void pollDueFiles() {
    try {
      FileRetentionExecutor.RetentionRun run = retentionExecutor.processDue(clock.now());
      if (run.purged() > 0 || run.skipped() > 0) {
        log.info(
            "File retention batch: examined={} purged={} skipped(storageFailures)={}",
            run.examined(),
            run.purged(),
            run.skipped());
      }
    } catch (Exception ex) {
      log.error("Failed to process due file retentions", ex);
    }
  }
}
