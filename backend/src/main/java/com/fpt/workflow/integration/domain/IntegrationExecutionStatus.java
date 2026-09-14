package com.fpt.workflow.integration.domain;

public enum IntegrationExecutionStatus {
  RUNNING,
  SUCCEEDED,
  COMPLETED,
  FAILED,
  WAITING_CALLBACK,
  MANUAL_RECONCILIATION;

  public boolean isSucceeded() {
    return this == SUCCEEDED || this == COMPLETED;
  }
}
