package com.fpt.workflow.shared.domain;

import java.util.Objects;

public final class OptimisticVersionGuard {

  private OptimisticVersionGuard() {}

  public static void requireMatch(AggregateVersion current, ExpectedVersion expected) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(expected, "expected");
    if (current.value() != expected.value()) {
      throw new StaleAggregateVersionException(expected, current);
    }
  }
}
