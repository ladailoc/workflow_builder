package com.fpt.workflow.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

public record SystemActionAttemptOutcome(
    boolean terminal, SystemActionResult terminalResult, Duration retryDelay, JsonNode error) {
  public static SystemActionAttemptOutcome terminal(SystemActionResult result) {
    return new SystemActionAttemptOutcome(true, result, null, null);
  }

  public static SystemActionAttemptOutcome idempotentReplay() {
    return new SystemActionAttemptOutcome(true, null, null, null);
  }

  public static SystemActionAttemptOutcome retry(Duration delay, JsonNode error) {
    return new SystemActionAttemptOutcome(false, null, delay, error);
  }
}
