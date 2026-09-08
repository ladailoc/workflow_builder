package com.fpt.workflow.shared.domain.lifecycle;

public enum EventStatus implements LifecycleState {
  CREATED,
  RUNNING,
  WAITING,
  COMPLETED,
  FAILED,
  CANCELLED,
  TERMINATED
}
