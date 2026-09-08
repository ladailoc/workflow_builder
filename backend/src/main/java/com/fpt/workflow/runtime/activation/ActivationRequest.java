package com.fpt.workflow.runtime.activation;

import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.util.Objects;
import java.util.UUID;

public record ActivationRequest(
    UUID eventId,
    UUID targetNodeDefinitionId,
    ActivationKey activationKey,
    UUID cycleId,
    int iteration,
    String pathToken,
    String itemToken,
    UUID splitScopeId,
    UUID joinScopeId,
    CorrelationId correlationId,
    CommandId commandId) {

  public ActivationRequest {
    eventId = Objects.requireNonNull(eventId, "eventId");
    targetNodeDefinitionId =
        Objects.requireNonNull(targetNodeDefinitionId, "targetNodeDefinitionId");
    activationKey = Objects.requireNonNull(activationKey, "activationKey");
    cycleId = Objects.requireNonNull(cycleId, "cycleId");
    if (iteration < 0) throw new IllegalArgumentException("iteration must not be negative");
    if (pathToken == null || pathToken.isBlank()) {
      throw new IllegalArgumentException("pathToken must not be blank");
    }
    correlationId = Objects.requireNonNull(correlationId, "correlationId");
  }

  public static ActivationRequest root(
      UUID eventId,
      UUID startNodeId,
      UUID cycleId,
      CorrelationId correlationId,
      CommandId commandId) {
    return new ActivationRequest(
        eventId,
        startNodeId,
        ActivationKey.root(eventId, startNodeId),
        cycleId,
        0,
        "root",
        null,
        null,
        null,
        correlationId,
        commandId);
  }
}
