package com.fpt.workflow.runtime.activation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Stable semantic identity for a node occurrence; never based on NodeDefinition alone. */
public record ActivationKey(String value) {

  public ActivationKey {
    if (value == null || value.isBlank() || value.length() > 512) {
      throw new IllegalArgumentException("Invalid activation key");
    }
  }

  public static ActivationKey root(UUID eventId, UUID startNodeId) {
    return derived("root", eventId, startNodeId, "root", null, null);
  }

  public static ActivationKey downstream(
      UUID eventId,
      UUID sourceExecutionId,
      UUID edgeId,
      String pathToken,
      UUID cycleId,
      String itemToken) {
    return derived(
        "edge", eventId, sourceExecutionId, edgeId + ":" + pathToken, cycleId, itemToken);
  }

  private static ActivationKey derived(
      String kind, UUID first, UUID second, String scope, UUID cycleId, String itemToken) {
    Objects.requireNonNull(first, "first");
    Objects.requireNonNull(second, "second");
    String canonical =
        String.join(
            "|",
            kind,
            first.toString(),
            second.toString(),
            Objects.requireNonNull(scope, "scope"),
            cycleId == null ? "-" : cycleId.toString(),
            itemToken == null ? "-" : itemToken);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return new ActivationKey(kind + ":" + HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }
}
