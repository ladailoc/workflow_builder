package com.fpt.workflow.shared.time;

import java.time.Instant;

/** Injectable source for persisted and externally visible timestamps. */
@FunctionalInterface
public interface PlatformClock {

  Instant now();
}
