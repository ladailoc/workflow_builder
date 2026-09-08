package com.fpt.workflow.shared.domain.lifecycle;

public enum TaskStatus implements LifecycleState {
  READY,
  CLAIMED,
  IN_PROGRESS,
  COMPLETED,
  CANCELLED,
  EXPIRED
}
