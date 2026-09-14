package com.fpt.workflow.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Outbound connector call request. The timeout in milliseconds is resolved from the action's
 * retry/execution configuration (§12.6: external calls must be bounded and never run inside a
 * long DB transaction); adapters must apply it to the outbound I/O.
 */
public record IntegrationCallRequest(
    UUID executionId,
    String connectorKey,
    String actionKey,
    int actionVersion,
    String idempotencyKey,
    JsonNode inputData,
    String credentialRef,
    JsonNode executionConfig) {

  /** Bounded outbound timeout in milliseconds; never null. */
  public long timeoutMs() {
    JsonNode config = executionConfig;
    if (config != null) {
      if (config.hasNonNull("timeoutMs")) {
        return Math.max(1, config.get("timeoutMs").asLong(DEFAULT_TIMEOUT_MS));
      }
      if (config.hasNonNull("timeoutSeconds")) {
        return Math.max(1, config.get("timeoutSeconds").asLong() * 1000L);
      }
    }
    return DEFAULT_TIMEOUT_MS;
  }

  public static final long DEFAULT_TIMEOUT_MS = 10_000L;
}
