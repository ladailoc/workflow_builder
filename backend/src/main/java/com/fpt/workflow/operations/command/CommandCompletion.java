package com.fpt.workflow.operations.command;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record CommandCompletion(JsonNode resultJson, JsonNode resultMetadataJson) {

  public CommandCompletion {
    resultJson = Objects.requireNonNull(resultJson, "resultJson").deepCopy();
    resultMetadataJson =
        Objects.requireNonNull(resultMetadataJson, "resultMetadataJson").deepCopy();
    if (!resultMetadataJson.isObject()) {
      throw new IllegalArgumentException("resultMetadataJson must be a JSON object");
    }
  }

  @Override
  public JsonNode resultJson() {
    return resultJson.deepCopy();
  }

  @Override
  public JsonNode resultMetadataJson() {
    return resultMetadataJson.deepCopy();
  }
}
