package com.fpt.workflow.integration.domain;

public enum IntegrationErrorCategory {
  NONE,
  SERVICE_UNAVAILABLE_503,
  TIMEOUT,
  LOST_RESPONSE,
  NETWORK_ERROR,
  HTTP_5XX,
  HTTP_4XX,
  CONFIGURATION_ERROR,
  CLIENT_ERROR
}
