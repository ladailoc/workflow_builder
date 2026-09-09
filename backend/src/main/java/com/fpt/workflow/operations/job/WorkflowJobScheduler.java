package com.fpt.workflow.operations.job;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Continuously drains durable workflow jobs in deployed application profiles. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "platform.jobs.worker.enabled", havingValue = "true")
public class WorkflowJobScheduler {

  private static final Logger log = LoggerFactory.getLogger(WorkflowJobScheduler.class);
  private final WorkflowJobWorker worker;
  private final String workerId = "workflow-worker-" + UUID.randomUUID();
  private final int batchSize;
  private final Duration lease;

  public WorkflowJobScheduler(
      WorkflowJobWorker worker,
      @org.springframework.beans.factory.annotation.Value("${platform.jobs.worker.batch-size:10}")
          int batchSize,
      @org.springframework.beans.factory.annotation.Value(
              "${platform.jobs.worker.lease-seconds:30}")
          long leaseSeconds) {
    this.worker = worker;
    this.batchSize = batchSize;
    this.lease = Duration.ofSeconds(leaseSeconds);
  }

  @Scheduled(fixedDelayString = "${platform.jobs.worker.fixed-delay-ms:1000}")
  public void drain() {
    try {
      worker.runOnce(workerId, batchSize, lease);
    } catch (RuntimeException failure) {
      log.error("Durable workflow job polling failed", failure);
    }
  }
}
