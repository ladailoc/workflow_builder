package com.fpt.workflow.runtime.join.domain;

/** Synchronization completion policies for parallel branch joins. */
public enum JoinPolicy {
  AND,
  FIRST,
  N_OF_M;

  public static JoinPolicy fromString(String val) {
    if (val == null || val.isBlank()) return AND;
    String normalized = val.trim().toUpperCase(java.util.Locale.ROOT);
    if ("ANY".equals(normalized) || "XOR".equals(normalized)) {
      return FIRST;
    }
    if ("ALL".equals(normalized)) {
      return AND;
    }
    try {
      return valueOf(normalized);
    } catch (IllegalArgumentException e) {
      return AND;
    }
  }
}
