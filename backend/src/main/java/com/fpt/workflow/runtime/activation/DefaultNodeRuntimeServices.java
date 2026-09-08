package com.fpt.workflow.runtime.activation;

import com.fpt.workflow.nodetype.NodeRuntimeServices;
import com.fpt.workflow.nodetype.NotificationSchedulingPort;
import com.fpt.workflow.operations.job.WorkflowJobTransactions;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class DefaultNodeRuntimeServices implements NodeRuntimeServices {

  private final PlatformClock clock;
  private final UuidGenerator uuidGenerator;
  private final WorkflowJobTransactions jobs;
  private final NotificationSchedulingPort notifications;

  public DefaultNodeRuntimeServices(
      PlatformClock clock,
      UuidGenerator uuidGenerator,
      WorkflowJobTransactions jobs,
      NotificationSchedulingPort notifications) {
    this.clock = clock;
    this.uuidGenerator = uuidGenerator;
    this.jobs = jobs;
    this.notifications = notifications;
  }

  @Override
  public Instant now() {
    return clock.now();
  }

  @Override
  public UUID newId() {
    return uuidGenerator.generate();
  }

  @Override
  public void scheduleDurableJob(
      String jobType,
      UUID aggregateId,
      com.fasterxml.jackson.databind.JsonNode payload,
      int maxAttempts,
      Instant nextRunAt,
      String dedupKey) {
    jobs.enqueue(jobType, "NODE_EXECUTION", aggregateId, payload, maxAttempts, nextRunAt, dedupKey);
  }

  @Override
  public void scheduleNotification(
      UUID nodeExecutionId,
      com.fasterxml.jackson.databind.JsonNode input,
      com.fasterxml.jackson.databind.JsonNode configuration,
      String dedupKey) {
    notifications.schedule(nodeExecutionId, input, configuration, dedupKey);
  }
}
