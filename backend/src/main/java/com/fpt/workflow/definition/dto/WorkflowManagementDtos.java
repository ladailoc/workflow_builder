package com.fpt.workflow.definition.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.ValidationSeverity;
import com.fpt.workflow.definition.validation.CompilerIssue;
import com.fpt.workflow.definition.validation.ValidationCompilation;
import com.fpt.workflow.form.dto.WorkflowFormDtos;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkflowManagementDtos {

  private WorkflowManagementDtos() {}

  public record Summary(
      UUID id,
      String key,
      String name,
      String description,
      WorkflowDefinitionLifecycle lifecycle,
      UUID ownerId,
      UUID currentPublishedVersionId,
      Integer currentPublishedVersionNo,
      Instant lastPublishedAt,
      UUID activeDraftVersionId,
      Integer activeDraftVersionNo,
      Long activeDraftRevision,
      long versionCount,
      Instant createdAt,
      Instant updatedAt,
      long lockVersion) {}

  public record Detail(Summary workflow, List<WorkflowVersionDtos.View> versions) {
    public Detail {
      versions = List.copyOf(versions);
    }
  }

  public record VersionDetail(
      WorkflowVersionDtos.View version,
      List<WorkflowGraphDtos.NodeView> nodes,
      List<WorkflowGraphDtos.EdgeView> edges,
      List<WorkflowFormDtos.View> forms) {
    public VersionDetail {
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
      forms = List.copyOf(forms);
    }
  }

  public record RequestTypeAdminView(
      UUID id,
      String key,
      String name,
      String description,
      String category,
      boolean active,
      JsonNode creationPolicyJson,
      UUID workflowDefinitionId,
      String workflowDefinitionName,
      WorkflowDefinitionLifecycle workflowLifecycle,
      UUID currentPublishedVersionId,
      Integer currentPublishedVersionNo,
      boolean schemaAvailable,
      Instant createdAt,
      Instant updatedAt,
      long lockVersion) {}

  public record ValidationView(
      UUID workflowVersionId,
      long revision,
      String definitionChecksum,
      boolean valid,
      boolean publishable,
      List<ValidationIssueView> issues) {
    public ValidationView {
      issues = List.copyOf(issues);
    }

    public static ValidationView from(ValidationCompilation compilation) {
      return new ValidationView(
          compilation.workflowVersionId(),
          compilation.revision(),
          compilation.definitionChecksum(),
          compilation.valid(),
          compilation.publishable(),
          compilation.issues().stream().map(ValidationIssueView::from).toList());
    }

    public static ValidationView from(
        com.fpt.workflow.definition.domain.WorkflowValidationRun run,
        List<com.fpt.workflow.definition.domain.WorkflowValidationIssue> issues) {
      return new ValidationView(
          run.getWorkflowVersionId(),
          run.getRevision(),
          run.getDefinitionChecksum(),
          run.isValid(),
          run.isPublishable(),
          issues.stream().map(ValidationIssueView::from).toList());
    }
  }

  public record ValidationIssueView(
      String code,
      ValidationSeverity severity,
      String resourceType,
      UUID resourceId,
      String fieldPath,
      String message,
      String suggestion,
      JsonNode metadata) {
    public static ValidationIssueView from(CompilerIssue issue) {
      return new ValidationIssueView(
          issue.code(),
          issue.severity(),
          issue.resourceType(),
          issue.resourceId(),
          issue.fieldPath(),
          issue.message(),
          issue.suggestion(),
          issue.metadata());
    }

    public static ValidationIssueView from(
        com.fpt.workflow.definition.domain.WorkflowValidationIssue issue) {
      return new ValidationIssueView(
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

  public record LifecycleCommand(String reason) {}

  public record PublishCommand(
      long expectedRevision,
      java.util.Set<String> acknowledgedWarnings) {
    public PublishCommand(long expectedRevision) {
      this(expectedRevision, java.util.Set.of());
    }
  }

  public record SaveTicketForm(JsonNode schemaJson, long expectedRevision) {}

  public record SaveGraph(WorkflowGraphDtos.ReplaceGraph graph, long expectedRevision) {}

  public record UpdateWorkflow(String name, String description, UUID ownerId) {}

  public record UpdateRequestType(
      String name,
      String description,
      String category,
      UUID workflowDefinitionId,
      JsonNode creationPolicyJson) {}

  public record RequestTypeActivation(String reason) {}

  public record VersionRef(
      UUID id, int versionNo, WorkflowVersionStatus status, long revision, long lockVersion) {}
}
