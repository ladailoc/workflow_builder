package com.fpt.workflow.definition.validation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ValidationCompilation(
    UUID workflowVersionId,
    long revision,
    String definitionChecksum,
    List<ValidationStage> executedStages,
    List<CompilerIssue> issues) {

  public ValidationCompilation {
    Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    Objects.requireNonNull(definitionChecksum, "definitionChecksum");
    executedStages = List.copyOf(executedStages);
    issues = List.copyOf(issues);
  }

  public boolean valid() {
    return issues.stream()
        .noneMatch(
            issue ->
                issue.severity() == com.fpt.workflow.definition.domain.ValidationSeverity.ERROR);
  }

  public boolean publishable() {
    return publishable(java.util.Set.of());
  }

  public boolean publishable(java.util.Set<String> acknowledgedWarnings) {
    if (!valid()) {
      return false;
    }
    java.util.Set<String> acks =
        acknowledgedWarnings == null ? java.util.Set.of() : acknowledgedWarnings;
    return issues.stream()
        .filter(
            issue ->
                issue.severity()
                    == com.fpt.workflow.definition.domain.ValidationSeverity.ACK_REQUIRED_WARNING)
        .allMatch(issue -> acks.contains(issue.code()) || acks.contains("*"));
  }

  public boolean hasErrors() {
    return !valid();
  }

  public boolean hasAckRequiredWarnings() {
    return issues.stream()
        .anyMatch(
            issue ->
                issue.severity()
                    == com.fpt.workflow.definition.domain.ValidationSeverity.ACK_REQUIRED_WARNING);
  }
}
