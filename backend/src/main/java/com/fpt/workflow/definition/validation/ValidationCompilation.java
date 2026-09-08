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
    return valid()
        && issues.stream()
            .noneMatch(
                issue ->
                    issue.severity()
                        == com.fpt.workflow.definition.domain.ValidationSeverity
                            .ACK_REQUIRED_WARNING);
  }
}
