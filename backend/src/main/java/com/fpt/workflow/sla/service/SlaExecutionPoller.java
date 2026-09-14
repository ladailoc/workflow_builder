package com.fpt.workflow.sla.service;

import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.time.PlatformClock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
    name = "platform.sla.poller.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SlaExecutionPoller {

  private static final Logger log = LoggerFactory.getLogger(SlaExecutionPoller.class);
  private final SlaActionExecutor executor;
  private final PlatformClock clock;

  public SlaExecutionPoller(SlaActionExecutor executor, PlatformClock clock) {
    this.executor = executor;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${platform.sla.poller.fixed-delay-ms:5000}")
  public void pollDueSlas() {
    try {
      executor.executeDue(
          clock.now(), new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));
    } catch (Exception ex) {
      log.error("Failed to execute due SLAs", ex);
    }
  }
}
