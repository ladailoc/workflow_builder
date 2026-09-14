package com.fpt.workflow.operations.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.outbox.transport", havingValue = "noop")
public class NoOpOutboxTransport implements OutboxTransport {
  @Override
  public void publish(OutboxEvent event) {}
}
