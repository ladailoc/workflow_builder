package com.fpt.workflow.shared.domain.lifecycle;

public enum WorkflowVersionStatus implements LifecycleState {
  DRAFT,
  PUBLISHED,
  SUPERSEDED,
  ARCHIVED
}
