package com.fpt.workflow.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.integration.domain.IntegrationErrorCategory;

public record IntegrationCallResponse(
    boolean success,
    int statusCode,
    JsonNode payload,
    IntegrationErrorCategory errorCategory,
    String errorMessage) {

  public static IntegrationCallResponse success(int statusCode, JsonNode payload) {
    return new IntegrationCallResponse(
        true,
        statusCode,
        payload != null ? payload : JsonNodeFactory.instance.objectNode(),
        IntegrationErrorCategory.NONE,
        null);
  }

  public static IntegrationCallResponse failure(
      IntegrationErrorCategory errorCategory,
      int statusCode,
      String errorMessage,
      JsonNode payload) {
    return new IntegrationCallResponse(
        false,
        statusCode,
        payload != null ? payload : JsonNodeFactory.instance.objectNode(),
        errorCategory != null ? errorCategory : IntegrationErrorCategory.CLIENT_ERROR,
        errorMessage);
  }

  public static IntegrationCallResponse serviceUnavailable(String message) {
    return failure(IntegrationErrorCategory.SERVICE_UNAVAILABLE_503, 503, message, null);
  }

  public static IntegrationCallResponse timeout(String message) {
    return failure(IntegrationErrorCategory.TIMEOUT, 0, message, null);
  }

  public static IntegrationCallResponse lostResponse(String message) {
    return failure(IntegrationErrorCategory.LOST_RESPONSE, 0, message, null);
  }

  public static IntegrationCallResponse networkError(String message) {
    return failure(IntegrationErrorCategory.NETWORK_ERROR, 0, message, null);
  }
}
