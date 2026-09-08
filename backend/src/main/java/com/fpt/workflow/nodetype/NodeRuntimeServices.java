package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Narrow deterministic runtime services. Routing/activation are intentionally not exposed. */
public interface NodeRuntimeServices {

  Instant now();

  UUID newId();

  default void scheduleDurableJob(
      String jobType,
      UUID aggregateId,
      JsonNode payload,
      int maxAttempts,
      Instant nextRunAt,
      String dedupKey) {
    throw new IllegalStateException("Durable job scheduling was not supplied");
  }

  default void scheduleNotification(
      UUID nodeExecutionId, JsonNode input, JsonNode configuration, String dedupKey) {
    throw new IllegalStateException("Notification scheduling was not supplied");
  }

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
