package com.fpt.workflow.operations.outbox;

public interface OutboxTransport {
  void publish(OutboxEvent event);
}
