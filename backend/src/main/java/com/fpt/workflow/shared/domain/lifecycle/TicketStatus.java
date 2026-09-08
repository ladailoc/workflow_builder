package com.fpt.workflow.shared.domain.lifecycle;

public enum TicketStatus implements LifecycleState {
  DRAFT,
  SUBMITTED,
  IN_PROGRESS,
  COMPLETED,
  REJECTED,
  CANCELLED
}
