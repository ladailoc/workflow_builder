package com.fpt.workflow.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "request_types")
public class RequestType {

  @Id private UUID id;

  @Column(name = "key", nullable = false, length = 128)
  private String key;

  @Column(nullable = false, length = 200)
  private String name;

  @Column private String description;

  @Column(nullable = false, length = 100)
  private String category;

  @Column(name = "workflow_definition_id", nullable = false)
  private UUID workflowDefinitionId;

  @Column(nullable = false)
  private boolean active;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "creation_policy_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode creationPolicyJson;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected RequestType() {}

  private RequestType(
      UUID id,
      String key,
      String name,
      String description,
      String category,
      UUID workflowDefinitionId,
      boolean active,
      JsonNode creationPolicyJson,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.key = DefinitionValues.key(key);
    this.name = DefinitionValues.requiredText(name, "name");
    this.description = description;
    this.category = DefinitionValues.requiredText(category, "category");
    this.workflowDefinitionId =
        Objects.requireNonNull(workflowDefinitionId, "workflowDefinitionId");
    this.active = active;
    this.creationPolicyJson = DefinitionValues.object(creationPolicyJson, "creationPolicyJson");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = createdAt;
  }

  public static RequestType create(
      UUID id,
      String key,
      String name,
      String description,
      String category,
      UUID workflowDefinitionId,
      boolean active,
      JsonNode creationPolicyJson,
      Instant createdAt) {
    return new RequestType(
        id,
        key,
        name,
        description,
        category,
        workflowDefinitionId,
        active,
        creationPolicyJson,
        createdAt);
  }

  public void update(
      String name,
      String description,
      String category,
      UUID workflowDefinitionId,
      boolean active,
      JsonNode creationPolicyJson,
      Instant updatedAt) {
    this.name = DefinitionValues.requiredText(name, "name");
    this.description = description;
    this.category = DefinitionValues.requiredText(category, "category");
    this.workflowDefinitionId =
        Objects.requireNonNull(workflowDefinitionId, "workflowDefinitionId");
    this.active = active;
    this.creationPolicyJson = DefinitionValues.object(creationPolicyJson, "creationPolicyJson");
    Instant nextUpdatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (nextUpdatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
    this.updatedAt = nextUpdatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getKey() {
    return key;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getCategory() {
    return category;
  }

  public UUID getWorkflowDefinitionId() {
    return workflowDefinitionId;
  }

  public boolean isActive() {
    return active;
  }

  public JsonNode getCreationPolicyJson() {
    return creationPolicyJson.deepCopy();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
