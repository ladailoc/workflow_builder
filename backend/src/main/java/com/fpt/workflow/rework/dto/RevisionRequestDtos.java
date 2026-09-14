package com.fpt.workflow.rework.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public final class RevisionRequestDtos {
  private RevisionRequestDtos() {}

  public record SubmitRevisionRequest(
      JsonNode values, JsonNode replacementTicketData, String changeReason) {}

  /** Ticket revision chain after a submitted REQUEST_REVISION (v2.4.1 §7.5). */
  public record RevisionSubmitView(
      UUID ticketId,
      long dataRevision,
      UUID currentRevisionId,
      UUID revisionFormSubmissionId,
      UUID revisionTicketRevisionId,
      Long workflowInputRevision) {}
}
