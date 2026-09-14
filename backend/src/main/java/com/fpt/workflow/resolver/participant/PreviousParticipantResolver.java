package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves previous participant/actor from execution history per workflow_spec.md §9.2.
 */
@Component
public final class PreviousParticipantResolver implements ParticipantResolver {

  public static final String TYPE = "PREVIOUS_PARTICIPANT";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public Set<String> configProperties() {
    return Set.of("type", "nodeKey", "offset", "fallbackToCreator");
  }

  @Override
  public UUID resolve(ParticipantResolverContext context) {
    ParticipantResolutionResult result = resolveResult(context);
    return result.singleUser()
        .orElseThrow(
            () -> new IllegalArgumentException("PREVIOUS_PARTICIPANT resolution failed: " + result.reason()));
  }

  @Override
  public ParticipantResolutionResult resolveResult(ParticipantResolverContext context) {
    JsonNode executionData = context.executionData();
    String targetNodeKey = context.config().path("nodeKey").asText(null);

    UUID resolvedUser = null;
    if (targetNodeKey != null && executionData.has(targetNodeKey)) {
      JsonNode nodeExec = executionData.get(targetNodeKey);
      String userStr = nodeExec.path("assigneeId").asText(nodeExec.path("actorId").asText(null));
      if (userStr != null) {
        try {
          resolvedUser = UUID.fromString(userStr);
        } catch (IllegalArgumentException ignored) {
        }
      }
    }

    if (resolvedUser == null && executionData.has("previousActorId")) {
      try {
        resolvedUser = UUID.fromString(executionData.path("previousActorId").asText());
      } catch (IllegalArgumentException ignored) {
      }
    }

    if (resolvedUser != null) {
      return ParticipantResolutionResult.resolved(resolvedUser, TYPE);
    }

    if (context.config().path("fallbackToCreator").asBoolean(false)) {
      return ParticipantResolutionResult.resolved(context.creatorId(), TYPE);
    }

    return ParticipantResolutionResult.notFound(
        "Previous participant not found for nodeKey: " + targetNodeKey, TYPE);
  }
}
