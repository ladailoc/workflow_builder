package com.fpt.workflow.definition.rework;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Strict bounded policy stored on a REWORK/RETURN Edge config. */
public record ReworkPolicy(
    int maxIterations,
    ReworkExhaustionAction onExhausted,
    String exhaustionPort,
    ReworkScope scope,
    boolean allowParallelScopeReset) {

  public ReworkPolicy {
    if (maxIterations < 1) throw new IllegalArgumentException("maxIterations must be positive");
    onExhausted = Objects.requireNonNull(onExhausted, "onExhausted");
    scope = Objects.requireNonNull(scope, "scope");
    if (onExhausted != ReworkExhaustionAction.FAIL_EVENT
        && (exhaustionPort == null || exhaustionPort.isBlank())) {
      throw new IllegalArgumentException(onExhausted + " requires exhaustionPort");
    }
    if (exhaustionPort != null) exhaustionPort = exhaustionPort.trim();
  }

  public static ReworkPolicy fromEdgeConfig(JsonNode edgeConfig) {
    JsonNode policy = edgeConfig == null ? null : edgeConfig.get("reworkPolicy");
    if (policy == null || !policy.isObject()) {
      throw new IllegalArgumentException("REWORK/RETURN edge requires reworkPolicy");
    }
    if (!policy.hasNonNull("maxIterations")
        || !policy.hasNonNull("onExhausted")
        || !policy.hasNonNull("scope")) {
      throw new IllegalArgumentException(
          "reworkPolicy requires maxIterations, onExhausted, and scope");
    }
    return new ReworkPolicy(
        policy.path("maxIterations").asInt(),
        ReworkExhaustionAction.valueOf(policy.path("onExhausted").asText()),
        policy.path("exhaustionPort").isTextual() ? policy.path("exhaustionPort").asText() : null,
        ReworkScope.valueOf(policy.path("scope").asText()),
        policy.path("allowParallelScopeReset").asBoolean(false));
  }
}
