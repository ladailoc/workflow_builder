package com.fpt.workflow.integration.domain;

public enum IntegrationExecutionStatus {
  RUNNING,
  COMPLETED,
  FAILED,
  WAITING_CALLBACK,
  MANUAL_RECONCILIATION
}
