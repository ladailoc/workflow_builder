package com.fpt.workflow.definition.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.RequestType;
import java.time.Instant;
import java.util.UUID;

public final class RequestTypeDtos {

  private RequestTypeDtos() {}

  public record Create(
      String key,
      String name,
      String description,
      String category,
      UUID workflowDefinitionId,
      boolean active,
      JsonNode creationPolicyJson) {}

  public record Update(
      String name,
      String description,
      String category,
      UUID workflowDefinitionId,
      boolean active,
      JsonNode creationPolicyJson) {}

  public record View(
      UUID id,
      String key,
      String name,
      String description,
      String category,
      UUID workflowDefinitionId,
      boolean active,
      JsonNode creationPolicyJson,
      Instant createdAt,
      Instant updatedAt,
      long lockVersion) {

    public static View from(RequestType requestType) {
      return new View(
          requestType.getId(),
          requestType.getKey(),
          requestType.getName(),
          requestType.getDescription(),
          requestType.getCategory(),
          requestType.getWorkflowDefinitionId(),
          requestType.isActive(),
          requestType.getCreationPolicyJson(),
          requestType.getCreatedAt(),
          requestType.getUpdatedAt(),
          requestType.getLockVersion());
    }
  }
}
