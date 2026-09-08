package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

final class ParticipantItemIds {
  private ParticipantItemIds() {}

  static UUID requiredUserId(JsonNode item) {
    if (item == null || item.isNull()) {
      throw new IllegalArgumentException("Multi-instance user item is missing");
    }
    String raw = item.isTextual() ? item.asText() : item.path("id").asText(null);
    if (raw == null) {
      throw new IllegalArgumentException("Multi-instance user item requires a UUID id");
    }
    return UUID.fromString(raw);
  }
}
