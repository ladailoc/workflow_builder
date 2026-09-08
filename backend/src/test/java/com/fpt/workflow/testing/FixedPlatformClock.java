package com.fpt.workflow.testing;

import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;

public final class FixedPlatformClock implements PlatformClock {

  private final Instant instant;

  public FixedPlatformClock(Instant instant) {
    this.instant = instant;
  }

  @Override
  public Instant now() {
    return instant;
  }
}
