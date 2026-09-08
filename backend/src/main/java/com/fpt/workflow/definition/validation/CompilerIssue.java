package com.fpt.workflow.definition.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.ValidationSeverity;
import java.util.Objects;
import java.util.UUID;

public record CompilerIssue(
    String code,
    ValidationSeverity severity,
    String resourceType,
    UUID resourceId,
    String fieldPath,
    String message,
    String suggestion,
    JsonNode metadata) {

  public CompilerIssue {
    if (code == null || !code.matches("[A-Z][A-Z0-9_.]*")) {
      throw new IllegalArgumentException("Validation issue code must be stable upper snake case");
    }
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(fieldPath, "fieldPath");
    Objects.requireNonNull(message, "message");
    metadata = metadata == null ? JsonNodeFactory.instance.objectNode() : metadata.deepCopy();
  }
}
