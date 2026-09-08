package com.fpt.workflow.definition.domain;

/** Design-time edge semantics. Runtime routing behavior is implemented separately. */
public enum TransitionType {
  NORMAL,
  CONDITIONAL,
  REWORK,
  RETURN
}
