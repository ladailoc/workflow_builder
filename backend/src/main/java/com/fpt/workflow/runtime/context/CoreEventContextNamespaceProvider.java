package com.fpt.workflow.runtime.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Minimal authoritative projection until the organization persistence wave is implemented. */
@Component
public final class CoreEventContextNamespaceProvider implements EventContextNamespaceProvider {

  @Override
  public ObjectNode creator(TicketContextSnapshot ticket) {
    ObjectNode creator = JsonNodeFactory.instance.objectNode();
    creator.put("id", ticket.creatorId().toString());
    return creator;
  }

  @Override
  public ObjectNode organization(TicketContextSnapshot ticket) {
    return JsonNodeFactory.instance.objectNode();
  }
}
