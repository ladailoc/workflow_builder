package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Dependency-inversion port used by generic node runtime to persist notification intent. */
public interface NotificationSchedulingPort {
  void schedule(UUID nodeExecutionId, JsonNode input, JsonNode configuration, String dedupKey);
}
