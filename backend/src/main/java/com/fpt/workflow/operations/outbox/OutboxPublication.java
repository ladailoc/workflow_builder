package com.fpt.workflow.operations.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Immutable application-bus envelope delivered by the default outbox transport. */
public record OutboxPublication(
    UUID id,
    String eventType,
    String aggregateType,
    UUID aggregateId,
    JsonNode payload,
    String dedupKey) {

  public OutboxPublication {
    payload = payload.deepCopy();
  }

  public JsonNode payload() {
    return payload.deepCopy();
  }
}
