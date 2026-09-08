package com.fpt.workflow.shared.domain;

public record ExpectedVersion(long value) {

  public ExpectedVersion {
    if (value < 0) {
      throw new IllegalArgumentException("Expected version must not be negative");
    }
  }
}
