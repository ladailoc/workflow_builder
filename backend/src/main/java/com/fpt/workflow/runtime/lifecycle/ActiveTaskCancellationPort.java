package com.fpt.workflow.runtime.lifecycle;

import java.time.Instant;
import java.util.UUID;

/**
 * Port for propagating cancellation to active human tasks without direct coupling to task entity.
 */
@FunctionalInterface
public interface ActiveTaskCancellationPort {

  void cancelActiveTasks(UUID nodeExecutionId, Instant cancelledAt);
}
