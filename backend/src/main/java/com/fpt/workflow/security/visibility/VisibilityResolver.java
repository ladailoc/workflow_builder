package com.fpt.workflow.security.visibility;

import com.fpt.workflow.security.ActorContext;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;

/** Central server-side policy for Ticket/Event visibility. */
public interface VisibilityResolver {

  VisibilityDecision resolveTicket(ActorContext actor, UUID ticketId);

  VisibilityDecision resolveEvent(ActorContext actor, UUID eventId);

  default boolean mayViewTicket(ActorContext actor, UUID ticketId) {
    return resolveTicket(actor, ticketId).allowed();
  }

  default boolean mayViewEvent(ActorContext actor, UUID eventId) {
    return resolveEvent(actor, eventId).allowed();
  }

  default void requireTicketVisible(ActorContext actor, UUID ticketId) {
    if (!mayViewTicket(actor, ticketId)) {
      throw new AccessDeniedException("Ticket is not visible to the authenticated actor");
    }
  }

  default void requireEventVisible(ActorContext actor, UUID eventId) {
    if (!mayViewEvent(actor, eventId)) {
      throw new AccessDeniedException("Event is not visible to the authenticated actor");
    }
  }

  record VisibilityDecision(boolean allowed, VisibilitySubject subject) {
    public static VisibilityDecision allow(VisibilitySubject subject) {
      return new VisibilityDecision(true, subject);
    }

    public static VisibilityDecision deny() {
      return new VisibilityDecision(false, VisibilitySubject.NONE);
    }
  }

  enum VisibilitySubject {
    PRIVILEGED_OPERATOR,
    CREATOR,
    WORKFLOW_OWNER,
    PARTICIPANT,
    NONE
  }
}
