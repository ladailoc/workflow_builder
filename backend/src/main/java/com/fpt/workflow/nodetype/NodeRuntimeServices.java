package com.fpt.workflow.nodetype;

import java.time.Instant;
import java.util.UUID;

/** Narrow deterministic runtime services. Routing/activation are intentionally not exposed. */
public interface NodeRuntimeServices {

  Instant now();

  UUID newId();

  static NodeRuntimeServices unavailable() {
    return new NodeRuntimeServices() {
      @Override
      public Instant now() {
        throw new IllegalStateException("Runtime services were not supplied");
      }

      @Override
      public UUID newId() {
        throw new IllegalStateException("Runtime services were not supplied");
      }
    };
  }
}
