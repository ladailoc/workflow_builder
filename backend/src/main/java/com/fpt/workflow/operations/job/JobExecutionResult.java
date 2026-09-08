package com.fpt.workflow.operations.job;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

public sealed interface JobExecutionResult
    permits JobExecutionResult.Success,
        JobExecutionResult.Retry,
        JobExecutionResult.PermanentFailure {
  record Success() implements JobExecutionResult {}

  record Retry(Duration delay, JsonNode error) implements JobExecutionResult {}

  record PermanentFailure(JsonNode error) implements JobExecutionResult {}

  static Success success() {
    return new Success();
  }

  static Retry retry(Duration delay, JsonNode error) {
    return new Retry(delay, error);
  }

  static PermanentFailure dead(JsonNode error) {
    return new PermanentFailure(error);
  }
}
