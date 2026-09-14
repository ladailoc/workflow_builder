package com.fpt.workflow.file.domain;

public enum FileScanStatus {
  PENDING_SCAN,
  CLEAN,
  QUARANTINED,
  REJECTED,
  /** Tombstone: object-storage bytes purged after retention expiry; metadata row retained. */
  RETENTION_PURGED
}
