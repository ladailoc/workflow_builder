package com.fpt.workflow.shared.domain.lifecycle;

import java.util.Locale;
import java.util.regex.Pattern;

public record BusinessOutcome(String value) {

  private static final Pattern VALUE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  public static final BusinessOutcome APPROVED = new BusinessOutcome("APPROVED");
  public static final BusinessOutcome REJECTED = new BusinessOutcome("REJECTED");
  public static final BusinessOutcome SUCCESS = new BusinessOutcome("SUCCESS");
  public static final BusinessOutcome WITHDRAWN = new BusinessOutcome("WITHDRAWN");
  public static final BusinessOutcome RETURNED = new BusinessOutcome("RETURNED");
  public static final BusinessOutcome SUBMITTED = new BusinessOutcome("SUBMITTED");
  public static final BusinessOutcome ERROR = new BusinessOutcome("ERROR");
  public static final BusinessOutcome FIRED = new BusinessOutcome("FIRED");

  public BusinessOutcome {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Business outcome must not be blank");
    }
    value = value.trim().toUpperCase(Locale.ROOT);
    if (!VALUE_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid business outcome");
    }
  }

  public static BusinessOutcome of(String value) {
    return new BusinessOutcome(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
