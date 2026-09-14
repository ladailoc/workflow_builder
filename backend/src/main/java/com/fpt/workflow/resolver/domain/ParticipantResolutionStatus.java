package com.fpt.workflow.resolver.domain;

/**
 * Normative resolution status per workflow_spec.md §9.4:
 * ResolutionResult.status = RESOLVED | VACANT | INACTIVE_ASSIGNEE | NOT_FOUND | AMBIGUOUS
 */
public enum ParticipantResolutionStatus {
  RESOLVED,
  VACANT,
  INACTIVE_ASSIGNEE,
  NOT_FOUND,
  AMBIGUOUS,
  // Backward-compatible aliases
  NO_MATCH,
  FAILED;

  public boolean isResolved() {
    return this == RESOLVED;
  }
}
