package com.fpt.workflow.connector.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "connector_action_versions",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"connector_action_id", "version_no"})})
public class ConnectorActionVersion {

  @Id private UUID id;

  @Column(name = "connector_action_id", nullable = false)
  private UUID connectorActionId;

  @Column(name = "version_no", nullable = false)
  private int versionNo;

  @Column(nullable = false, length = 32)
  private String status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "input_schema_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode inputSchemaJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "output_schema_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode outputSchemaJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "execution_config_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode executionConfigJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "retry_policy_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode retryPolicyJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "idempotency_policy_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode idempotencyPolicyJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "error_mapping_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode errorMappingJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "permission_policy_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode permissionPolicyJson;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected ConnectorActionVersion() {}

  public static ConnectorActionVersion publish(
      UUID id,
      UUID connectorActionId,
      int versionNo,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode executionConfigJson,
      JsonNode retryPolicyJson,
      JsonNode idempotencyPolicyJson,
      JsonNode errorMappingJson,
      JsonNode permissionPolicyJson,
      Instant now) {
    if (versionNo < 1) {
      throw new IllegalArgumentException("Action version number must be positive: " + versionNo);
    }
    ConnectorDefinition.validateNoSecrets(executionConfigJson);

    ConnectorActionVersion v = new ConnectorActionVersion();
    v.id = Objects.requireNonNull(id, "id");
    v.connectorActionId = Objects.requireNonNull(connectorActionId, "connectorActionId");
    v.versionNo = versionNo;
    v.status = "PUBLISHED";
    v.inputSchemaJson =
        inputSchemaJson != null
            ? inputSchemaJson.deepCopy()
            : JsonNodeFactory.instance.objectNode();
    v.outputSchemaJson =
        outputSchemaJson != null
            ? outputSchemaJson.deepCopy()
            : JsonNodeFactory.instance.objectNode();
    v.executionConfigJson =
        executionConfigJson != null
            ? executionConfigJson.deepCopy()
            : JsonNodeFactory.instance.objectNode();
    v.retryPolicyJson =
        retryPolicyJson != null
            ? retryPolicyJson.deepCopy()
            : JsonNodeFactory.instance.objectNode();
    v.idempotencyPolicyJson =
        idempotencyPolicyJson != null
            ? idempotencyPolicyJson.deepCopy()
            : JsonNodeFactory.instance.objectNode();
    v.errorMappingJson =
        errorMappingJson != null
            ? errorMappingJson.deepCopy()
            : JsonNodeFactory.instance.objectNode();
    v.permissionPolicyJson =
        permissionPolicyJson != null
            ? permissionPolicyJson.deepCopy()
            : JsonNodeFactory.instance.objectNode();
    v.createdAt = Objects.requireNonNull(now, "now");
    return v;
  }

  public void deprecate() {
    this.status = "DEPRECATED";
  }

  public void requireMutable() {
    if ("PUBLISHED".equals(status) || "DEPRECATED".equals(status)) {
      throw new IllegalStateException(
          "Connector action version " + versionNo + " is " + status + " and immutable");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getConnectorActionId() {
    return connectorActionId;
  }

  public int getVersionNo() {
    return versionNo;
  }

  public String getStatus() {
    return status;
  }

  public JsonNode getInputSchemaJson() {
    return inputSchemaJson != null ? inputSchemaJson.deepCopy() : null;
  }

  public JsonNode getOutputSchemaJson() {
    return outputSchemaJson != null ? outputSchemaJson.deepCopy() : null;
  }

  public JsonNode getExecutionConfigJson() {
    return executionConfigJson != null ? executionConfigJson.deepCopy() : null;
  }

  public JsonNode getRetryPolicyJson() {
    return retryPolicyJson != null ? retryPolicyJson.deepCopy() : null;
  }

  public JsonNode getIdempotencyPolicyJson() {
    return idempotencyPolicyJson != null ? idempotencyPolicyJson.deepCopy() : null;
  }

  public JsonNode getErrorMappingJson() {
    return errorMappingJson != null ? errorMappingJson.deepCopy() : null;
  }

  public JsonNode getPermissionPolicyJson() {
    return permissionPolicyJson != null ? permissionPolicyJson.deepCopy() : null;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
