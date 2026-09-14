package com.fpt.workflow.operations.outbox;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lightweight poller; the database outbox remains the scheduling source of truth. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "platform.outbox.publisher.enabled", havingValue = "true")
public class OutboxPublisherScheduler {
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

  private final OutboxPublisher publisher;
  private final String workerId = "outbox-publisher-" + UUID.randomUUID();
  private final int batchSize;
  private final Duration lease;
  private final Duration retryDelay;

  public OutboxPublisherScheduler(
      OutboxPublisher publisher,
      @Value("${platform.outbox.publisher.batch-size:20}") int batchSize,
      @Value("${platform.outbox.publisher.lease-seconds:30}") long leaseSeconds,
      @Value("${platform.outbox.publisher.retry-delay-seconds:5}") long retryDelaySeconds) {
    if (batchSize <= 0 || leaseSeconds <= 0 || retryDelaySeconds < 0) {
      throw new IllegalArgumentException("Invalid outbox publisher scheduling configuration");
    }
    this.publisher = publisher;
    this.batchSize = batchSize;
    this.lease = Duration.ofSeconds(leaseSeconds);
    this.retryDelay = Duration.ofSeconds(retryDelaySeconds);
  }

  @Scheduled(fixedDelayString = "${platform.outbox.publisher.fixed-delay-ms:1000}")
  public void drain() {
    try {
      int claimed = publisher.publishAvailable(workerId, batchSize, lease, retryDelay);
      if (claimed > 0) log.debug("Outbox publisher {} processed {} record(s)", workerId, claimed);
    } catch (RuntimeException failure) {
      log.error("Durable outbox polling failed", failure);
    }
  }
}
