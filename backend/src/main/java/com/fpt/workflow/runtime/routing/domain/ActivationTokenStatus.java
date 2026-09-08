package com.fpt.workflow.runtime.routing.domain;

/** Lifecycle states for an ActivationToken. Terminal: ACTIVATED, SKIPPED, CANCELLED. */
public enum ActivationTokenStatus {
  PENDING,
  ACTIVATED,
  SKIPPED,
  CANCELLED
}
