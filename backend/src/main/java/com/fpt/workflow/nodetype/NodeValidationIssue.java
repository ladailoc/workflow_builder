package com.fpt.workflow.nodetype;

import java.util.Objects;

public record NodeValidationIssue(
    String code, NodeValidationSeverity severity, String fieldPath, String message) {

  public NodeValidationIssue {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    severity = Objects.requireNonNull(severity, "severity");
    if (fieldPath == null || fieldPath.isBlank()) {
      throw new IllegalArgumentException("fieldPath must not be blank");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
  }

  public boolean blocking() {
    return severity == NodeValidationSeverity.ERROR;
  }
}
