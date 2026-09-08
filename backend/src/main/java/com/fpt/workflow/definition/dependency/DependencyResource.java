package com.fpt.workflow.definition.dependency;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.UUID;

public record DependencyResource(
    DependencyResourceType resourceType, UUID resourceId, String fieldPath, JsonNode content) {

  public DependencyResource {
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(fieldPath, "fieldPath");
    Objects.requireNonNull(content, "content");
  }
}
