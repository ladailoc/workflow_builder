package com.fpt.workflow.shared.domain.value;

import java.util.List;
import java.util.Objects;

public record CanonicalValidationResult(List<CanonicalValidationIssue> issues) {

  public CanonicalValidationResult {
    issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
  }

  public boolean valid() {
    return issues.isEmpty();
  }

  public void requireValid() {
    if (!valid()) {
      CanonicalValidationIssue first = issues.getFirst();
      throw new IllegalArgumentException(
          first.code() + " at " + first.path() + ": " + first.message());
    }
  }
}
