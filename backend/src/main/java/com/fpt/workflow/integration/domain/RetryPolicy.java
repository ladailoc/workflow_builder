package com.fpt.workflow.integration.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public record RetryPolicy(
    boolean idempotent,
    int maxRetries,
    long backoffMs,
    Set<IntegrationErrorCategory> retryableErrors) {

  public RetryPolicy {
    Objects.requireNonNull(retryableErrors, "retryableErrors");
    retryableErrors = Collections.unmodifiableSet(new HashSet<>(retryableErrors));
  }

  public static RetryPolicy defaultPolicy() {
    return new RetryPolicy(
        true,
        3,
        50,
        Set.of(
            IntegrationErrorCategory.SERVICE_UNAVAILABLE_503,
            IntegrationErrorCategory.TIMEOUT,
            IntegrationErrorCategory.LOST_RESPONSE,
            IntegrationErrorCategory.NETWORK_ERROR,
            IntegrationErrorCategory.HTTP_5XX));
  }

  public static RetryPolicy nonIdempotentPolicy() {
    return new RetryPolicy(false, 0, 0, Set.of());
  }

  public boolean canRetry(IntegrationErrorCategory errorCategory, int attemptNumber) {
    if (errorCategory == null || errorCategory == IntegrationErrorCategory.NONE) {
      return false;
    }
    if (!idempotent) {
      // Non-idempotent action: no blind automatic retry.
      return false;
    }
    if (attemptNumber > maxRetries) {
      // Retries exhausted
      return false;
    }
    return retryableErrors.contains(errorCategory);
  }

  public static RetryPolicy fromJson(JsonNode node) {
    if (node == null || node.isNull() || !node.isObject()) {
      return defaultPolicy();
    }
    boolean idempotent = !node.has("idempotent") || node.get("idempotent").asBoolean(true);
    int maxRetries = node.has("maxRetries") ? node.get("maxRetries").asInt(3) : 3;
    long backoffMs = node.has("backoffMs") ? node.get("backoffMs").asLong(0) : 0;
    Set<IntegrationErrorCategory> retryables = new HashSet<>();
    if (node.has("retryableErrors") && node.get("retryableErrors").isArray()) {
      for (JsonNode item : node.get("retryableErrors")) {
        try {
          retryables.add(IntegrationErrorCategory.valueOf(item.asText()));
        } catch (IllegalArgumentException ignored) {
        }
      }
    } else {
      retryables.addAll(
          Set.of(
              IntegrationErrorCategory.SERVICE_UNAVAILABLE_503,
              IntegrationErrorCategory.TIMEOUT,
              IntegrationErrorCategory.LOST_RESPONSE,
              IntegrationErrorCategory.NETWORK_ERROR,
              IntegrationErrorCategory.HTTP_5XX));
    }
    return new RetryPolicy(idempotent, maxRetries, backoffMs, retryables);
  }
}
