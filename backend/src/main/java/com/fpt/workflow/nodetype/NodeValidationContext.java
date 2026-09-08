package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record NodeValidationContext(String nodeKey, int configSchemaVersion, JsonNode config) {

  public NodeValidationContext {
    if (nodeKey == null || nodeKey.isBlank()) {
      throw new IllegalArgumentException("nodeKey must not be blank");
    }
    if (configSchemaVersion < 1) {
      throw new IllegalArgumentException("configSchemaVersion must be positive");
    }
    config = Objects.requireNonNull(config, "config").deepCopy();
  }
}
