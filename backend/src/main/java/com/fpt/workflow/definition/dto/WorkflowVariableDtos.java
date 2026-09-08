package com.fpt.workflow.definition.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.VariableScope;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.UUID;

public final class WorkflowVariableDtos {

  private WorkflowVariableDtos() {}

  public record Create(
      UUID workflowVersionId,
      String key,
      TypeDescriptor type,
      VariableScope scope,
      JsonNode defaultJson,
      boolean mutable,
      boolean sensitive) {}

  public record Update(
      TypeDescriptor type,
      VariableScope scope,
      JsonNode defaultJson,
      boolean mutable,
      boolean sensitive) {}

  public record View(
      UUID id,
      UUID workflowVersionId,
      String key,
      TypeDescriptor type,
      VariableScope scope,
      JsonNode defaultJson,
      boolean mutable,
      boolean sensitive) {

    public static View from(WorkflowVariable variable) {
      return new View(
          variable.getId(),
          variable.getWorkflowVersionId(),
          variable.getKey(),
          variable.getType(),
          variable.getScope(),
          variable.getDefaultJson(),
          variable.isMutable(),
          variable.isSensitive());
    }
  }
}
