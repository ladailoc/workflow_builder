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
@Table(name = "workflow_edges")
public class EdgeDefinition {

  @Id private UUID id;

  @Column(name = "workflow_version_id", nullable = false)
  private UUID workflowVersionId;

  @Column(name = "source_node_id", nullable = false)
  private UUID sourceNodeId;

  @Column(name = "source_port", nullable = false, length = 128)
  private String sourcePort;

  @Column(name = "target_node_id", nullable = false)
  private UUID targetNodeId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "condition_json", columnDefinition = "jsonb")
  private JsonNode conditionJson;

  @Column(nullable = false)
  private int priority;

  @Column(name = "is_default", nullable = false)
  private boolean defaultTransition;

  @Enumerated(EnumType.STRING)
  @Column(name = "transition_type", nullable = false, length = 32)
  private TransitionType transitionType;

  @Column(length = 255)
  private String label;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode configJson;

  protected EdgeDefinition() {}

  private EdgeDefinition(
      UUID id,
      UUID workflowVersionId,
      UUID sourceNodeId,
      String sourcePort,
      UUID targetNodeId,
      JsonNode conditionJson,
      int priority,
      boolean defaultTransition,
      TransitionType transitionType,
      String label,
      JsonNode configJson) {
    this.id = Objects.requireNonNull(id, "id");
    this.workflowVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    applyDefinition(
        sourceNodeId,
        sourcePort,
        targetNodeId,
        conditionJson,
        priority,
        defaultTransition,
        transitionType,
        label,
        configJson);
  }

  public static EdgeDefinition create(
      UUID id,
      UUID workflowVersionId,
      UUID sourceNodeId,
      String sourcePort,
      UUID targetNodeId,
      JsonNode conditionJson,
      int priority,
      boolean defaultTransition,
      TransitionType transitionType,
      String label,
      JsonNode configJson) {
    return new EdgeDefinition(
        id,
        workflowVersionId,
        sourceNodeId,
        sourcePort,
        targetNodeId,
        conditionJson,
        priority,
        defaultTransition,
        transitionType,
        label,
        configJson);
  }

  public void update(
      UUID sourceNodeId,
      String sourcePort,
      UUID targetNodeId,
      JsonNode conditionJson,
      int priority,
      boolean defaultTransition,
      TransitionType transitionType,
      String label,
      JsonNode configJson) {
    applyDefinition(
        sourceNodeId,
        sourcePort,
        targetNodeId,
        conditionJson,
        priority,
        defaultTransition,
        transitionType,
        label,
        configJson);
  }

  private void applyDefinition(
      UUID sourceNodeId,
      String sourcePort,
      UUID targetNodeId,
      JsonNode conditionJson,
      int priority,
      boolean defaultTransition,
      TransitionType transitionType,
      String label,
      JsonNode configJson) {
    if (priority < 0) {
      throw new IllegalArgumentException("priority must not be negative");
    }
    this.sourceNodeId = Objects.requireNonNull(sourceNodeId, "sourceNodeId");
    this.sourcePort = DefinitionValues.key(sourcePort);
    this.targetNodeId = Objects.requireNonNull(targetNodeId, "targetNodeId");
    this.conditionJson = GraphValues.nullableObject(conditionJson, "conditionJson");
    this.priority = priority;
    this.defaultTransition = defaultTransition;
    this.transitionType = Objects.requireNonNull(transitionType, "transitionType");
    this.label = label;
    this.configJson = GraphValues.object(configJson, "configJson");
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkflowVersionId() {
    return workflowVersionId;
  }

  public UUID getSourceNodeId() {
    return sourceNodeId;
  }

  public String getSourcePort() {
    return sourcePort;
  }

  public UUID getTargetNodeId() {
    return targetNodeId;
  }

  public JsonNode getConditionJson() {
    return conditionJson == null ? null : conditionJson.deepCopy();
  }

  public int getPriority() {
    return priority;
  }

  public boolean isDefaultTransition() {
    return defaultTransition;
  }

  public TransitionType getTransitionType() {
    return transitionType;
  }

  public String getLabel() {
    return label;
  }

  public JsonNode getConfigJson() {
    return configJson.deepCopy();
  }
}
