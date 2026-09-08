package com.fpt.workflow.definition.domain;

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
@Table(name = "workflow_validation_issues")
public class WorkflowValidationIssue {

  @Id private UUID id;

  @Column(name = "validation_run_id", nullable = false)
  private UUID validationRunId;

  @Column(name = "rule_code", nullable = false, length = 128)
  private String ruleCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ValidationSeverity severity;

  @Column(name = "resource_type", nullable = false, length = 128)
  private String resourceType;

  @Column(name = "resource_id", nullable = false)
  private UUID resourceId;

  @Column(name = "field_path", length = 512)
  private String fieldPath;

  @Column(nullable = false)
  private String message;

  @Column private String suggestion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode metadataJson;

  protected WorkflowValidationIssue() {}

  private WorkflowValidationIssue(
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
    this.id = Objects.requireNonNull(id, "id");
    this.validationRunId = Objects.requireNonNull(validationRunId, "validationRunId");
    this.ruleCode = ValidationValues.ruleCode(ruleCode);
    this.severity = Objects.requireNonNull(severity, "severity");
    this.resourceType = DefinitionValues.key(resourceType);
    this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
    this.fieldPath = ValidationValues.optionalText(fieldPath);
    this.message = ValidationValues.requiredText(message, "message");
    this.suggestion = ValidationValues.optionalText(suggestion);
    this.metadataJson = ValidationValues.metadata(metadataJson);
  }

  public static WorkflowValidationIssue create(
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
    return new WorkflowValidationIssue(
        id,
        validationRunId,
        ruleCode,
        severity,
        resourceType,
        resourceId,
        fieldPath,
        message,
        suggestion,
        metadataJson);
  }

  public UUID getId() {
    return id;
  }

  public UUID getValidationRunId() {
    return validationRunId;
  }

  public String getRuleCode() {
    return ruleCode;
  }

  public ValidationSeverity getSeverity() {
    return severity;
  }

  public String getResourceType() {
    return resourceType;
  }

  public UUID getResourceId() {
    return resourceId;
  }

  public String getFieldPath() {
    return fieldPath;
  }

  public String getMessage() {
    return message;
  }

  public String getSuggestion() {
    return suggestion;
  }

  public JsonNode getMetadataJson() {
    return metadataJson.deepCopy();
  }
}
