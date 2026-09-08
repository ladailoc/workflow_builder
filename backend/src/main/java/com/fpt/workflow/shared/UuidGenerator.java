package com.fpt.workflow.shared;

import java.util.UUID;

/** Injectable UUID source so request and domain identifiers are deterministic in tests. */
@FunctionalInterface
public interface UuidGenerator {

  UUID generate();
}
