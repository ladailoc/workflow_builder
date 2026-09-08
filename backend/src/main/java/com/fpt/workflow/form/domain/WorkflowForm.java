package com.fpt.workflow.form.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_forms")
public class WorkflowForm {

  @Id private UUID id;

  @Column(name = "workflow_version_id", nullable = false)
  private UUID workflowVersionId;

  @Column(name = "form_key", nullable = false, length = 128)
  private String formKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "form_type", nullable = false, length = 32)
  private WorkflowFormType formType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "schema_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode schemaJson;

  @Column(name = "schema_checksum", nullable = false, length = 256)
  private String schemaChecksum;

  protected WorkflowForm() {}

  private WorkflowForm(
      UUID id,
      UUID workflowVersionId,
      String formKey,
      WorkflowFormType formType,
      JsonNode schemaJson,
      String schemaChecksum) {
    this.id = Objects.requireNonNull(id, "id");
    this.workflowVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    this.formKey = FormValues.key(formKey);
    applySchema(formType, schemaJson, schemaChecksum);
  }

  public static WorkflowForm create(
      UUID id,
      UUID workflowVersionId,
      String formKey,
      WorkflowFormType formType,
      JsonNode schemaJson,
      String schemaChecksum) {
    return new WorkflowForm(id, workflowVersionId, formKey, formType, schemaJson, schemaChecksum);
  }

  public void update(WorkflowFormType formType, JsonNode schemaJson, String schemaChecksum) {
    applySchema(formType, schemaJson, schemaChecksum);
  }

  private void applySchema(WorkflowFormType formType, JsonNode schemaJson, String schemaChecksum) {
    this.formType = Objects.requireNonNull(formType, "formType");
    this.schemaJson = FormValues.schema(schemaJson);
    this.schemaChecksum = FormValues.checksum(schemaChecksum);
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkflowVersionId() {
    return workflowVersionId;
  }

  public String getFormKey() {
    return formKey;
  }

  public WorkflowFormType getFormType() {
    return formType;
  }

  public JsonNode getSchemaJson() {
    return schemaJson.deepCopy();
  }

  public String getSchemaChecksum() {
    return schemaChecksum;
  }
}
