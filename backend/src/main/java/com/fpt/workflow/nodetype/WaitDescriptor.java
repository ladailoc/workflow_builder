package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record WaitDescriptor(String waitType, String correlationKey, JsonNode details) {

  public WaitDescriptor {
    if (waitType == null || waitType.isBlank()) {
      throw new IllegalArgumentException("waitType must not be blank");
    }
    if (correlationKey == null || correlationKey.isBlank()) {
      throw new IllegalArgumentException("correlationKey must not be blank");
    }
    details = Objects.requireNonNull(details, "details").deepCopy();
  }
}
