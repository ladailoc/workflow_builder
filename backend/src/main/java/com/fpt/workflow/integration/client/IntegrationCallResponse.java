package com.fpt.workflow.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.integration.domain.IntegrationErrorCategory;

public record IntegrationCallResponse(
    boolean success,
    int statusCode,
    JsonNode payload,
    IntegrationErrorCategory errorCategory,
    String errorMessage,
    String externalRequestId) {

  public IntegrationCallResponse(
      boolean success,
      int statusCode,
      JsonNode payload,
      IntegrationErrorCategory errorCategory,
      String errorMessage) {
    this(success, statusCode, payload, errorCategory, errorMessage, null);
  }

  public static IntegrationCallResponse success(int statusCode, JsonNode payload) {
    return success(statusCode, payload, null);
  }

  public static IntegrationCallResponse success(
      int statusCode, JsonNode payload, String externalRequestId) {
    return new IntegrationCallResponse(
        true,
        statusCode,
        payload != null ? payload : JsonNodeFactory.instance.objectNode(),
        IntegrationErrorCategory.NONE,
        null,
        externalRequestId);
  }

  public static IntegrationCallResponse failure(
      IntegrationErrorCategory errorCategory,
      int statusCode,
      String errorMessage,
      JsonNode payload) {
    return failure(errorCategory, statusCode, errorMessage, payload, null);
  }

  public static IntegrationCallResponse failure(
      IntegrationErrorCategory errorCategory,
      int statusCode,
      String errorMessage,
      JsonNode payload,
      String externalRequestId) {
    return new IntegrationCallResponse(
        false,
        statusCode,
        payload != null ? payload : JsonNodeFactory.instance.objectNode(),
        errorCategory != null ? errorCategory : IntegrationErrorCategory.CLIENT_ERROR,
        errorMessage,
        externalRequestId);
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
