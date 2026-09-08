package com.fpt.workflow.resolver.expression;

import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ExpressionValidationResult(
    List<ExpressionValidationIssue> issues, TypeDescriptor inferredType) {

  public ExpressionValidationResult {
    issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
  }

  public boolean valid() {
    return issues.isEmpty() && inferredType != null;
  }

  public Optional<TypeDescriptor> type() {
    return Optional.ofNullable(inferredType);
  }

  public void requireValid() {
    if (!valid()) {
      ExpressionValidationIssue first = issues.getFirst();
      throw new IllegalArgumentException(
          first.code() + " at " + first.path() + ": " + first.message());
    }
  }
}
