package com.fpt.workflow.operations.outbox;

import org.springframework.stereotype.Component;

@Component
public class NoOpOutboxTransport implements OutboxTransport {
  @Override
  public void publish(OutboxEvent event) {}
}
