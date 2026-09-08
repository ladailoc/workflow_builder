package com.fpt.workflow.shared.domain.value;

import java.util.Objects;

public record CanonicalValidationIssue(String code, String path, String message) {

  public CanonicalValidationIssue {
    code = required(code, "code");
    path = required(path, "path");
    message = required(message, "message");
  }

  private static String required(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
