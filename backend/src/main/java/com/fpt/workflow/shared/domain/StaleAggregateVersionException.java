package com.fpt.workflow.shared.domain;

import org.springframework.dao.OptimisticLockingFailureException;

public final class StaleAggregateVersionException extends OptimisticLockingFailureException {

  private final ExpectedVersion expected;
  private final AggregateVersion current;

  public StaleAggregateVersionException(ExpectedVersion expected, AggregateVersion current) {
    super(
        "Expected aggregate version "
            + expected.value()
            + " but current version is "
            + current.value());
    this.expected = expected;
    this.current = current;
  }

  public ExpectedVersion expected() {
    return expected;
  }

  public AggregateVersion current() {
    return current;
  }
}
