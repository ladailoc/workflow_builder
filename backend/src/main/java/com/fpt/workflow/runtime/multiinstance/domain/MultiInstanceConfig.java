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
    if (node == null || node.isMissingNode() || !node.hasNonNull("collectionPath")) {
      return null;
    }
    String collectionPath = node.get("collectionPath").asText();
    String itemVariable =
        node.hasNonNull("itemVariable") ? node.get("itemVariable").asText() : "item";
    ExecutionMode mode =
        node.hasNonNull("executionMode")
            ? ExecutionMode.valueOf(node.get("executionMode").asText("PARALLEL"))
            : ExecutionMode.PARALLEL;
    CompletionPolicy policy =
        node.hasNonNull("completionPolicy")
            ? CompletionPolicy.valueOf(node.get("completionPolicy").asText("ALL"))
            : CompletionPolicy.ALL;
    Integer threshold =
        node.hasNonNull("completionThreshold") ? node.get("completionThreshold").asInt() : null;
    RemainingItemPolicy remainingPolicy =
        node.hasNonNull("remainingItemPolicy")
            ? RemainingItemPolicy.valueOf(
                node.get("remainingItemPolicy").asText("CANCEL_REMAINING"))
            : RemainingItemPolicy.CANCEL_REMAINING;

    return new MultiInstanceConfig(
        collectionPath, itemVariable, mode, policy, threshold, remainingPolicy);
  }
}
