package com.fpt.workflow.form.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import java.util.UUID;

public final class WorkflowFormDtos {

  private WorkflowFormDtos() {}

  public record Create(
      UUID workflowVersionId,
      String formKey,
      WorkflowFormType formType,
      JsonNode schemaJson,
      String schemaChecksum) {}

  public record Update(WorkflowFormType formType, JsonNode schemaJson, String schemaChecksum) {}

  public record View(
      UUID id,
      UUID workflowVersionId,
      String formKey,
      WorkflowFormType formType,
      JsonNode schemaJson,
      String schemaChecksum) {

    public static View from(WorkflowForm form) {
      return new View(
          form.getId(),
          form.getWorkflowVersionId(),
          form.getFormKey(),
          form.getFormType(),
          form.getSchemaJson(),
          form.getSchemaChecksum());
    }
  }
}
