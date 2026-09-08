package com.fpt.workflow.resolver.expression;

public record ExpressionValidationIssue(String code, String path, String message) {
  public ExpressionValidationIssue {
    if (code == null
        || code.isBlank()
        || path == null
        || path.isBlank()
        || message == null
        || message.isBlank()) {
      throw new IllegalArgumentException("Expression validation issue fields must not be blank");
    }
  }
}
