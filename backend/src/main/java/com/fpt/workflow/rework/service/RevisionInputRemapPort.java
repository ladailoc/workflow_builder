package com.fpt.workflow.rework.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Category-owned remap for a submitted REQUEST_REVISION: reruns the SAME pinned Category mapping
 * contract against the new business data and appends a new immutable WorkflowInputRevision. The
 * port keeps rework decoupled from the ticket-category feature; legacy non-category tickets resolve
 * to an empty result and keep create-time behavior unchanged.
 */
public interface RevisionInputRemapPort {

  Optional<RemappedInputs> remap(
      UUID eventId,
      UUID workflowVersionId,
      UUID ticketId,
      UUID newTicketRevisionId,
      JsonNode businessData,
      UUID actorId,
      Instant now);

  record RemappedInputs(UUID formSubmissionId, long inputRevision) {}
}
