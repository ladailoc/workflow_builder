package com.fpt.workflow.runtime.subworkflow.domain;
import java.util.Locale;
public enum SubWorkflowFailureStrategy {
  ROUTE_FAILED,
  FAIL_PARENT,
  MANUAL_RECOVERY;

  public static SubWorkflowFailureStrategy fromString(String value) {
    if (value == null || value.isBlank()) {
      return ROUTE_FAILED;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "FAIL_PARENT", "FAIL_EVENT", "FAIL_NODE" -> FAIL_PARENT;
      case "MANUAL_RECOVERY", "CREATE_MANUAL_TASK", "MANUAL_RECONCILIATION" -> MANUAL_RECOVERY;
      default -> ROUTE_FAILED;
    };
  }
}
