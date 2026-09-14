package com.fpt.workflow.operations.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Default local transport. Deployments can replace it with a broker adapter by selecting another
 * {@code platform.outbox.transport} implementation.
 */
@Component
@ConditionalOnProperty(
    name = "platform.outbox.transport",
    havingValue = "application-event",
    matchIfMissing = true)
public class ApplicationEventOutboxTransport implements OutboxTransport {
  private final ApplicationEventPublisher events;

  public ApplicationEventOutboxTransport(ApplicationEventPublisher events) {
    this.events = events;
  }

  @Override
  public void publish(OutboxEvent event) {
    events.publishEvent(
        new OutboxPublication(
            event.getId(),
            event.getEventType(),
            event.getAggregateType(),
            event.getAggregateId(),
            event.getPayloadJson(),
            event.getDedupKey()));
  }
}
