package com.fpt.workflow.shared.domain.lifecycle;

public enum NodeExecutionStatus implements LifecycleState {
  CREATED,
  READY,
  RUNNING,
  WAITING,
  COMPLETED,
  FAILED,
  CANCELLED,
  SKIPPED
}
