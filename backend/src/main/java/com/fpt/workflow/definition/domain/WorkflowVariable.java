package com.fpt.workflow.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
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
@Table(name = "workflow_variables")
public class WorkflowVariable {

  @Id private UUID id;

  @Column(name = "workflow_version_id", nullable = false)
  private UUID workflowVersionId;

  @Column(nullable = false, length = 128)
  private String key;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "type_descriptor_json", nullable = false, columnDefinition = "jsonb")
  private TypeDescriptor type;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private VariableScope scope;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "default_json", columnDefinition = "jsonb")
  private JsonNode defaultJson;

  @Column(nullable = false)
  private boolean mutable;

  @Column(nullable = false)
  private boolean sensitive;

  protected WorkflowVariable() {}

  private WorkflowVariable(
      UUID id,
      UUID workflowVersionId,
      String key,
      TypeDescriptor type,
      VariableScope scope,
      JsonNode defaultJson,
      boolean mutable,
      boolean sensitive) {
    this.id = Objects.requireNonNull(id, "id");
    this.workflowVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    this.key = DefinitionValues.key(key);
    applyDefinition(type, scope, defaultJson, mutable, sensitive);
  }

  public static WorkflowVariable create(
      UUID id,
      UUID workflowVersionId,
      String key,
      TypeDescriptor type,
      VariableScope scope,
      JsonNode defaultJson,
      boolean mutable,
      boolean sensitive) {
    return new WorkflowVariable(
        id, workflowVersionId, key, type, scope, defaultJson, mutable, sensitive);
  }

  public void update(
      TypeDescriptor type,
      VariableScope scope,
      JsonNode defaultJson,
      boolean mutable,
      boolean sensitive) {
    applyDefinition(type, scope, defaultJson, mutable, sensitive);
  }

  private void applyDefinition(
      TypeDescriptor type,
      VariableScope scope,
      JsonNode defaultJson,
      boolean mutable,
      boolean sensitive) {
    this.type = Objects.requireNonNull(type, "type");
    this.scope = Objects.requireNonNull(scope, "scope");
    if (defaultJson != null) {
      CanonicalValueValidator.requireValid(type, defaultJson);
    }
    this.defaultJson = defaultJson == null ? null : defaultJson.deepCopy();
    this.mutable = mutable;
    this.sensitive = sensitive;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkflowVersionId() {
    return workflowVersionId;
  }

  public String getKey() {
    return key;
  }

  public TypeDescriptor getType() {
    return type;
  }

  public VariableScope getScope() {
    return scope;
  }

  public JsonNode getDefaultJson() {
    return defaultJson == null ? null : defaultJson.deepCopy();
  }

  public boolean isMutable() {
    return mutable;
  }

  public boolean isSensitive() {
    return sensitive;
  }
}
