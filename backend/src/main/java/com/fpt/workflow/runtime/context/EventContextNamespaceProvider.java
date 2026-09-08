package com.fpt.workflow.runtime.context;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Extension port for authoritative identity/organization projections. */
public interface EventContextNamespaceProvider {

  ObjectNode creator(TicketContextSnapshot ticket);

  ObjectNode organization(TicketContextSnapshot ticket);
}
