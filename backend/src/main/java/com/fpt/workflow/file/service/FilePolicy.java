package com.fpt.workflow.file.service;

import java.time.Duration;
import java.util.Set;

public record FilePolicy(
    Set<String> allowedMimeTypes,
    long maxFileSize,
    int maxFileCount,
    Duration retention,
    boolean sensitive) {
  public FilePolicy {
    allowedMimeTypes = Set.copyOf(allowedMimeTypes);
    if (maxFileSize <= 0) throw new IllegalArgumentException("maxFileSize must be positive");
    if (maxFileCount <= 0) throw new IllegalArgumentException("maxFileCount must be positive");
    if (retention != null && (retention.isNegative() || retention.isZero())) {
      throw new IllegalArgumentException("retention must be positive");
    }
  }
}
