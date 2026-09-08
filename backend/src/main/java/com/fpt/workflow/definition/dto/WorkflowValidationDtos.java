package com.fpt.workflow.definition.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.ValidationSeverity;
import com.fpt.workflow.definition.domain.WorkflowValidationIssue;
import com.fpt.workflow.definition.domain.WorkflowValidationRun;
import java.time.Instant;
import java.util.UUID;

public final class WorkflowValidationDtos {

  private WorkflowValidationDtos() {}

  public record CreateRun(
      UUID workflowVersionId,
      long revision,
      String definitionChecksum,
      boolean valid,
      boolean publishable,
      int errorCount,
      int warningCount,
      int infoCount) {}

  public record RunView(
      UUID id,
      UUID workflowVersionId,
      long revision,
      String definitionChecksum,
      boolean valid,
      boolean publishable,
      int errorCount,
      int warningCount,
      int infoCount,
      UUID validatedBy,
      Instant validatedAt) {

    public static RunView from(WorkflowValidationRun run) {
      return new RunView(
          run.getId(),
          run.getWorkflowVersionId(),
          run.getRevision(),
          run.getDefinitionChecksum(),
          run.isValid(),
          run.isPublishable(),
          run.getErrorCount(),
          run.getWarningCount(),
          run.getInfoCount(),
          run.getValidatedBy(),
          run.getValidatedAt());
    }
  }

  public record CreateIssue(
      UUID validationRunId,
      String ruleCode,
      ValidationSeverity severity,
      String resourceType,
      UUID resourceId,
      String fieldPath,
      String message,
      String suggestion,
      JsonNode metadataJson) {}

  public record IssueView(
      UUID id,
      UUID validationRunId,
      String ruleCode,
      ValidationSeverity severity,
      String resourceType,
      UUID resourceId,
      String fieldPath,
      String message,
      String suggestion,
      JsonNode metadataJson) {

    public static IssueView from(WorkflowValidationIssue issue) {
      return new IssueView(
          issue.getId(),
          issue.getValidationRunId(),
          issue.getRuleCode(),
          issue.getSeverity(),
          issue.getResourceType(),
          issue.getResourceId(),
          issue.getFieldPath(),
          issue.getMessage(),
          issue.getSuggestion(),
          issue.getMetadataJson());
    }
  }
}
