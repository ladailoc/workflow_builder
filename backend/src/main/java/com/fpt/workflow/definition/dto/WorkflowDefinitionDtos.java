package com.fpt.workflow.definition.dto;

import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import java.time.Instant;
import java.util.UUID;

public final class WorkflowDefinitionDtos {

  private WorkflowDefinitionDtos() {}

  public record Create(String key, String name, String description, UUID ownerId) {}

  public record Update(
      String name, String description, UUID ownerId, WorkflowDefinitionLifecycle lifecycle) {}

  public record View(
      UUID id,
      String key,
      String name,
      String description,
      WorkflowDefinitionLifecycle lifecycle,
      UUID ownerId,
      UUID currentPublishedVersionId,
      UUID activeDraftVersionId,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt,
      long lockVersion) {

    public static View from(WorkflowDefinition definition) {
      return new View(
          definition.getId(),
          definition.getKey(),
          definition.getName(),
          definition.getDescription(),
          definition.getLifecycle(),
          definition.getOwnerId(),
          definition.getCurrentPublishedVersionId(),
          definition.getActiveDraftVersionId(),
          definition.getCreatedBy(),
          definition.getCreatedAt(),
          definition.getUpdatedAt(),
          definition.getLockVersion());
    }
  }
}
