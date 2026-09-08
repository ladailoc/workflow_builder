package com.fpt.workflow.shared.domain;

import java.time.Instant;
import java.util.Objects;

public record LifecycleTimestamps(Instant createdAt, Instant updatedAt) {

  public LifecycleTimestamps {
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
  }

  public static LifecycleTimestamps createdAt(Instant instant) {
    return new LifecycleTimestamps(instant, instant);
  }

  public LifecycleTimestamps updatedAt(Instant instant) {
    return new LifecycleTimestamps(createdAt, instant);
  }
}
