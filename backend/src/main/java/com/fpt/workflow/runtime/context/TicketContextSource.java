package com.fpt.workflow.runtime.context;

import java.util.UUID;

/** Runtime read port for authoritative Ticket and immutable revision data. */
public interface TicketContextSource {

  TicketContextSnapshot load(UUID ticketId, UUID revisionId);
}
