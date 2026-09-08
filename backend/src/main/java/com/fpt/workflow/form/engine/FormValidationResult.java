package com.fpt.workflow.form.engine;

import java.util.List;
import java.util.Objects;

public record FormValidationResult(List<FormValidationIssue> issues) {
  public FormValidationResult {
    issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
  }

  public boolean valid() {
    return issues.stream().noneMatch(issue -> issue.severity() == FormIssueSeverity.ERROR);
  }
}
