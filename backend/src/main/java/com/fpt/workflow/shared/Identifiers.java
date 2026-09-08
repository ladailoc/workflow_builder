package com.fpt.workflow.shared;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Application-wide identifier generation convention for new aggregate and runtime IDs. */
@Component
public final class Identifiers implements UuidGenerator {

  @Override
  public UUID generate() {
    return newUuid();
  }

  public static UUID newUuid() {
    return UUID.randomUUID();
  }
}
