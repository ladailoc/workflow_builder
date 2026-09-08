package com.fpt.workflow.shared.domain;

public record AggregateVersion(long value) {

  public AggregateVersion {
    if (value < 0) {
      throw new IllegalArgumentException("Aggregate version must not be negative");
    }
  }

  public static AggregateVersion initial() {
    return new AggregateVersion(0);
  }

  public AggregateVersion next() {
    return new AggregateVersion(Math.addExact(value, 1));
  }
}
