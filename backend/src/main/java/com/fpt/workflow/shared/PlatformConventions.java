package com.fpt.workflow.shared;

import java.time.ZoneId;
import java.time.ZoneOffset;

public final class PlatformConventions {

  public static final String API_PREFIX = "/api/v1";
  public static final ZoneId PERSISTENCE_ZONE = ZoneOffset.UTC;

  private PlatformConventions() {}
}
