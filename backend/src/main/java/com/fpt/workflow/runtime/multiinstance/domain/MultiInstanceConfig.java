package com.fpt.workflow.runtime.multiinstance.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record MultiInstanceConfig(
    String collectionPath,
    String itemVariable,
    ExecutionMode executionMode,
    CompletionPolicy completionPolicy,
    Integer completionThreshold,
    RemainingItemPolicy remainingItemPolicy) {

  public static MultiInstanceConfig fromJson(JsonNode node) {
    if (node == null || node.isMissingNode() || (!node.hasNonNull("collectionPath") && !node.hasNonNull("collection"))) {
      return null;
    }
    String collectionPath =
        node.hasNonNull("collectionPath")
            ? node.get("collectionPath").asText()
            : node.get("collection").asText();

    String itemVariable =
        node.hasNonNull("itemVariable") ? node.get("itemVariable").asText() : "item";

    ExecutionMode mode = ExecutionMode.PARALLEL;
    if (node.hasNonNull("executionMode")) {
      try {
        mode = ExecutionMode.valueOf(node.get("executionMode").asText("PARALLEL").toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
      }
    }

    CompletionPolicy policy = CompletionPolicy.ALL;
    Integer threshold = null;
    if (node.hasNonNull("completionPolicy")) {
      JsonNode polNode = node.get("completionPolicy");
      if (polNode.isObject()) {
        if (polNode.hasNonNull("type")) {
          try {
            policy = CompletionPolicy.valueOf(polNode.get("type").asText("ALL").toUpperCase(java.util.Locale.ROOT));
          } catch (IllegalArgumentException ignored) {
          }
        }
        if (polNode.hasNonNull("threshold")) {
          threshold = polNode.get("threshold").asInt();
        }
      } else if (polNode.isTextual()) {
        try {
          policy = CompletionPolicy.valueOf(polNode.asText("ALL").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
      }
    }
    if (threshold == null && node.hasNonNull("completionThreshold")) {
      threshold = node.get("completionThreshold").asInt();
    }

    RemainingItemPolicy remainingPolicy = RemainingItemPolicy.CANCEL_REMAINING;
    if (node.hasNonNull("remainingItemPolicy")) {
      try {
        remainingPolicy =
            RemainingItemPolicy.valueOf(
                node.get("remainingItemPolicy").asText("CANCEL_REMAINING").toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
      }
    }

    return new MultiInstanceConfig(
        collectionPath, itemVariable, mode, policy, threshold, remainingPolicy);
  }
}
