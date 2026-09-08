package com.fpt.workflow.form.engine;

public record FormValidationIssue(
    String code, FormIssueSeverity severity, String fieldPath, String message) {

  public FormValidationIssue {
    if (code == null
        || code.isBlank()
        || fieldPath == null
        || fieldPath.isBlank()
        || message == null
        || message.isBlank()) {
      throw new IllegalArgumentException("Form validation issue fields must not be blank");
    }
  }
}
