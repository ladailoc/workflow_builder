package com.fpt.workflow.definition.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import java.time.Instant;
import java.util.UUID;

public final class WorkflowVersionDtos {

  private WorkflowVersionDtos() {}

  public record CreateDraft(UUID definitionId, UUID basedOnVersionId, UUID rollbackOfVersionId) {}

  public record UpdateDraft(
      long expectedRevision, String checksum, JsonNode executionPackageJson) {}

  public record View(
      UUID id,
      UUID definitionId,
      int versionNo,
      WorkflowVersionStatus status,
      long revision,
      String checksum,
      JsonNode executionPackageJson,
      UUID basedOnVersionId,
      UUID rollbackOfVersionId,
      UUID createdBy,
      Instant createdAt,
      UUID publishedBy,
      Instant publishedAt,
      long lockVersion) {

    public static View from(WorkflowVersion version) {
      return new View(
          version.getId(),
          version.getDefinitionId(),
          version.getVersionNo(),
          version.getStatus(),
          version.getRevision(),
          version.getChecksum(),
          version.getExecutionPackageJson(),
          version.getBasedOnVersionId(),
          version.getRollbackOfVersionId(),
          version.getCreatedBy(),
          version.getCreatedAt(),
          version.getPublishedBy(),
          version.getPublishedAt(),
          version.getLockVersion());
    }
  }
}
