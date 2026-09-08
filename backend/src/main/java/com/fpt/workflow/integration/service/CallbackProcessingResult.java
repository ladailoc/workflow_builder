package com.fpt.workflow.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.integration.domain.IntegrationCallbackStatus;
import java.util.UUID;

public record CallbackProcessingResult(
    IntegrationCallbackStatus status,
    UUID callbackId,
    UUID integrationExecutionId,
    UUID nodeExecutionId,
    String outcomePort,
    String message,
    JsonNode sanitizedPayload) {

  public boolean isAccepted() {
    return status == IntegrationCallbackStatus.ACCEPTED;
  }

  public boolean isDuplicate() {
    return status == IntegrationCallbackStatus.DUPLICATE;
  }

  public boolean isLate() {
    return status == IntegrationCallbackStatus.LATE;
  }

  public boolean isRejected() {
    return status == IntegrationCallbackStatus.REJECTED;
  }
}
