package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Runtime values supplied to a resolver without coupling the resolver module to Event/Task. */
public record ParticipantResolverContext(
    UUID creatorId, UUID referenceUserId, JsonNode item, JsonNode config, Instant resolvedAt) {

  public ParticipantResolverContext {
    creatorId = Objects.requireNonNull(creatorId, "creatorId");
    referenceUserId = Objects.requireNonNull(referenceUserId, "referenceUserId");
    item = item == null ? JsonNodeFactory.instance.nullNode() : item.deepCopy();
    if (config == null || !config.isObject()) {
      throw new IllegalArgumentException("Participant resolver config must be an object");
    }
    config = config.deepCopy();
    resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
  }
}
