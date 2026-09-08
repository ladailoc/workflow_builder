package com.fpt.workflow.runtime.join.domain;

/** Synchronization completion policies for parallel branch joins. */
public enum JoinPolicy {
  AND,
  FIRST,
  N_OF_M;

  public static JoinPolicy fromString(String val) {
    if (val == null || val.isBlank()) return AND;
    try {
      return valueOf(val.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      if ("XOR".equalsIgnoreCase(val)) return FIRST;
      return AND;
    }
  }
}
