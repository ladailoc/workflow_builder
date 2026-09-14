package com.fpt.workflow.runtime.lifecycle;

import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of TicketLifecycleSyncPort when ticket package is not loaded.
 */
@Component
@ConditionalOnMissingBean(name = "ticketLifecycleSyncService")
public class NoOpTicketLifecycleSyncPort implements TicketLifecycleSyncPort {

  @Override
  public void syncTicketStatus(
      UUID ticketId, EventStatus eventStatus, String outcome, Instant timestamp) {
    // no-op
  }
}
