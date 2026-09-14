package com.fpt.workflow.integration.domain;

import java.util.Locale;

/**
 * Normative failure strategies for Integration / SystemAction execution (§12.8 / §17.3).
 */
public enum IntegrationFailureStrategy {
  FAIL_NODE,
  FAIL_EVENT,
  GOTO_NODE,
  FALLBACK_ACTION,
  CONTINUE_WITH_WARNING,
  CREATE_MANUAL_TASK;

  public static IntegrationFailureStrategy fromString(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return IntegrationFailureStrategy.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
