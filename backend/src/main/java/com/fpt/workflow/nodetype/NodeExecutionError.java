package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record NodeExecutionError(String code, String message, JsonNode details) {

  public NodeExecutionError {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    details = Objects.requireNonNull(details, "details").deepCopy();
  }
}
