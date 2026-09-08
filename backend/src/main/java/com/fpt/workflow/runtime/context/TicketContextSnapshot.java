package com.fpt.workflow.runtime.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TicketContextSnapshot(
    UUID ticketId,
    UUID requestTypeId,
    UUID creatorId,
    String status,
    long dataRevision,
    UUID currentRevisionId,
    JsonNode currentData,
    UUID revisionId,
    long revisionNo,
    JsonNode revisionData,
    String sourceSchemaVersion,
    String schemaChecksum,
    Instant revisionSubmittedAt,
    List<TicketSubjectSnapshot> subjects) {

  public TicketContextSnapshot {
    ticketId = Objects.requireNonNull(ticketId, "ticketId");
    requestTypeId = Objects.requireNonNull(requestTypeId, "requestTypeId");
    creatorId = Objects.requireNonNull(creatorId, "creatorId");
    status = Objects.requireNonNull(status, "status");
    currentData = requireObject(currentData, "currentData");
    revisionId = Objects.requireNonNull(revisionId, "revisionId");
    if (revisionNo <= 0) {
      throw new IllegalArgumentException("revisionNo must be positive");
    }
    revisionData = requireObject(revisionData, "revisionData");
    sourceSchemaVersion = Objects.requireNonNull(sourceSchemaVersion, "sourceSchemaVersion");
    schemaChecksum = Objects.requireNonNull(schemaChecksum, "schemaChecksum");
    revisionSubmittedAt = Objects.requireNonNull(revisionSubmittedAt, "revisionSubmittedAt");
    subjects = List.copyOf(subjects);
  }

  private static JsonNode requireObject(JsonNode value, String field) {
    Objects.requireNonNull(value, field);
    if (!value.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return value.deepCopy();
  }
}
