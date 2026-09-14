package com.fpt.workflow.runtime.lifecycle;

import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Port for propagating Event aggregate lifecycle transitions to the associated Ticket without
 * direct coupling to the ticket domain.
 */
public interface TicketLifecycleSyncPort {

  void syncTicketStatus(UUID ticketId, EventStatus eventStatus, String outcome, Instant timestamp);
}
