package com.fpt.workflow.operations.command;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.UUID;

public record CommandExecutionResult(
    UUID executionId, JsonNode resultJson, JsonNode resultMetadataJson, boolean replayed) {

  public CommandExecutionResult {
    Objects.requireNonNull(executionId, "executionId");
    resultJson = Objects.requireNonNull(resultJson, "resultJson").deepCopy();
    resultMetadataJson =
        Objects.requireNonNull(resultMetadataJson, "resultMetadataJson").deepCopy();
  }

  public static CommandExecutionResult from(CommandExecution execution, boolean replayed) {
    if (execution.getStatus() != CommandExecutionStatus.SUCCEEDED) {
      throw new IllegalArgumentException("Only a successful command has a reusable result");
    }
    return new CommandExecutionResult(
        execution.getId(), execution.getResultJson(), execution.getResultMetadataJson(), replayed);
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
