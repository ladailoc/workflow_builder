package com.fpt.workflow.runtime.lifecycle;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(ActiveTaskCancellationPort.class)
public class NoOpActiveTaskCancellationPort implements ActiveTaskCancellationPort {

  @Override
  public void cancelActiveTasks(UUID nodeExecutionId, Instant cancelledAt) {}
}
