package com.fpt.workflow.shared.time;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public final class SystemPlatformClock implements PlatformClock {

  private final Clock clock;

  public SystemPlatformClock() {
    this(Clock.systemUTC());
  }

  SystemPlatformClock(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Instant now() {
    return clock.instant();
  }
}
