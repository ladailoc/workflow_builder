package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves participant user(s) from upstream node typed output per workflow_spec.md §9.2.
 */
@Component
public final class NodeOutputParticipantResolver implements ParticipantResolver {

  public static final String TYPE = "NODE_OUTPUT";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public Set<String> configProperties() {
    return Set.of("type", "nodeKey", "outputPath", "field");
  }

  @Override
  public UUID resolve(ParticipantResolverContext context) {
    ParticipantResolutionResult result = resolveResult(context);
    return result.singleUser()
        .orElseThrow(
            () -> new IllegalArgumentException("NODE_OUTPUT resolution failed: " + result.reason()));
  }

  @Override
  public ParticipantResolutionResult resolveResult(ParticipantResolverContext context) {
    String nodeKey = context.config().path("nodeKey").asText(null);
    String outputPath =
        context.config().path("outputPath").asText(context.config().path("field").asText("userId"));

    JsonNode executionData = context.executionData();
    JsonNode nodeOutput = null;
    if (nodeKey != null && executionData.has(nodeKey)) {
      JsonNode nodeData = executionData.get(nodeKey);
      nodeOutput =
          nodeData.has("latest")
              ? nodeData.path("latest").path("output")
              : nodeData.path("output");
    } else if (executionData.has("outputs")) {
      nodeOutput = executionData.path("outputs").path(nodeKey != null ? nodeKey : "");
    }

    if (nodeOutput == null || nodeOutput.isMissingNode() || nodeOutput.isNull()) {
      return ParticipantResolutionResult.notFound(
          "Node output missing for nodeKey: " + nodeKey, TYPE);
    }

    JsonNode targetNode = resolveSubpath(nodeOutput, outputPath);
    if (targetNode == null || targetNode.isMissingNode() || targetNode.isNull()) {
      return ParticipantResolutionResult.notFound(
          "Output path '" + outputPath + "' not found in node " + nodeKey, TYPE);
    }

    if (targetNode.isTextual()) {
      try {
        UUID user = UUID.fromString(targetNode.asText().trim());
        return ParticipantResolutionResult.resolved(user, TYPE);
      } catch (IllegalArgumentException ex) {
        return ParticipantResolutionResult.failed(
            "Value at path '" + outputPath + "' is not a valid UUID: " + targetNode.asText(), TYPE);
      }
    } else if (targetNode.isArray()) {
      List<UUID> users = new ArrayList<>();
      for (JsonNode item : targetNode) {
        if (item.isTextual()) {
          try {
            users.add(UUID.fromString(item.asText().trim()));
          } catch (IllegalArgumentException ignored) {
          }
        }
      }
      if (users.isEmpty()) {
        return ParticipantResolutionResult.notFound(
            "Array at path '" + outputPath + "' contains no valid UUIDs", TYPE);
      }
      return ParticipantResolutionResult.resolved(users, TYPE);
    }

    return ParticipantResolutionResult.failed(
        "Expected string or array of strings at '" + outputPath + "'", TYPE);
  }

  private JsonNode resolveSubpath(JsonNode root, String path) {
    if (root == null || path == null || path.isBlank()) return root;
    String[] parts = path.split("\\.");
    JsonNode current = root;
    for (String part : parts) {
      if (current == null || !current.has(part)) {
        return null;
      }
      current = current.get(part);
    }
    return current;
  }
}
