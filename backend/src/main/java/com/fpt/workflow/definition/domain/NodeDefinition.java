package com.fpt.workflow.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_nodes")
public class NodeDefinition {

  @Id private UUID id;

  @Column(name = "workflow_version_id", nullable = false)
  private UUID workflowVersionId;

  @Column(name = "node_key", nullable = false, length = 128)
  private String nodeKey;

  @Column(name = "node_type", nullable = false, length = 128)
  private String nodeType;

  @Column(nullable = false, length = 200)
  private String name;

  @Column private String description;

  @Column(name = "config_schema_version", nullable = false)
  private int configSchemaVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode configJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "input_schema_json", columnDefinition = "jsonb")
  private JsonNode inputSchemaJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "output_schema_json", columnDefinition = "jsonb")
  private JsonNode outputSchemaJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "position_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode positionJson;

  protected NodeDefinition() {}

  private NodeDefinition(
      UUID id,
      UUID workflowVersionId,
      String nodeKey,
      String nodeType,
      String name,
      String description,
      int configSchemaVersion,
      JsonNode configJson,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode positionJson) {
    this.id = Objects.requireNonNull(id, "id");
    this.workflowVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    this.nodeKey = DefinitionValues.key(nodeKey);
    applyDefinition(
        nodeType,
        name,
        description,
        configSchemaVersion,
        configJson,
        inputSchemaJson,
        outputSchemaJson,
        positionJson);
  }

  public static NodeDefinition create(
      UUID id,
      UUID workflowVersionId,
      String nodeKey,
      String nodeType,
      String name,
      String description,
      int configSchemaVersion,
      JsonNode configJson,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode positionJson) {
    return new NodeDefinition(
        id,
        workflowVersionId,
        nodeKey,
        nodeType,
        name,
        description,
        configSchemaVersion,
        configJson,
        inputSchemaJson,
        outputSchemaJson,
        positionJson);
  }

  public void update(
      String nodeType,
      String name,
      String description,
      int configSchemaVersion,
      JsonNode configJson,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode positionJson) {
    applyDefinition(
        nodeType,
        name,
        description,
        configSchemaVersion,
        configJson,
        inputSchemaJson,
        outputSchemaJson,
        positionJson);
  }

  private void applyDefinition(
      String nodeType,
      String name,
      String description,
      int configSchemaVersion,
      JsonNode configJson,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode positionJson) {
    if (configSchemaVersion < 1) {
      throw new IllegalArgumentException("configSchemaVersion must be positive");
    }
    this.nodeType = DefinitionValues.key(nodeType);
    this.name = DefinitionValues.requiredText(name, "name");
    this.description = description;
    this.configSchemaVersion = configSchemaVersion;
    this.configJson = GraphValues.nodeConfig(configJson);
    this.inputSchemaJson = GraphValues.nullableObject(inputSchemaJson, "inputSchemaJson");
    this.outputSchemaJson = GraphValues.nullableObject(outputSchemaJson, "outputSchemaJson");
    this.positionJson = GraphValues.object(positionJson, "positionJson");
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkflowVersionId() {
    return workflowVersionId;
  }

  public String getNodeKey() {
    return nodeKey;
  }

  public String getNodeType() {
    return nodeType;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public int getConfigSchemaVersion() {
    return configSchemaVersion;
  }

  public JsonNode getConfigJson() {
    return configJson.deepCopy();
  }

  public JsonNode getInputSchemaJson() {
    return inputSchemaJson == null ? null : inputSchemaJson.deepCopy();
  }

  public JsonNode getOutputSchemaJson() {
    return outputSchemaJson == null ? null : outputSchemaJson.deepCopy();
  }

  public JsonNode getPositionJson() {
    return positionJson.deepCopy();
  }
}
