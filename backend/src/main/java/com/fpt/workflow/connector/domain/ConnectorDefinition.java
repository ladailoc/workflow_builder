package com.fpt.workflow.connector.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
@Table(name = "connector_definitions")
public class ConnectorDefinition {

  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 64)
  private String key;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(name = "connector_type", nullable = false, length = 64)
  private String connectorType;

  @Column(name = "handler_key", nullable = false, length = 128)
  private String handlerKey;

  @Column(nullable = false, length = 32)
  private String status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode configJson;

  @Column(name = "credential_ref", length = 128)
  private String credentialRef;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected ConnectorDefinition() {}

  public static ConnectorDefinition create(
      UUID id,
      String key,
      String name,
      String connectorType,
      String handlerKey,
      String status,
      JsonNode configJson,
      String credentialRef,
      Instant now) {
    validateKey(key);
    validateNoSecrets(configJson);
    ConnectorDefinition def = new ConnectorDefinition();
    def.id = Objects.requireNonNull(id, "id");
    def.key = key;
    def.name = Objects.requireNonNull(name, "name");
    def.connectorType = Objects.requireNonNull(connectorType, "connectorType");
    def.handlerKey = Objects.requireNonNull(handlerKey, "handlerKey");
    def.status = status != null ? status : "ACTIVE";
    def.configJson =
        configJson != null ? configJson.deepCopy() : JsonNodeFactory.instance.objectNode();
    def.credentialRef = credentialRef;
    def.createdAt = Objects.requireNonNull(now, "now");
    def.updatedAt = now;
    return def;
  }

  public void update(
      String name,
      String handlerKey,
      String status,
      JsonNode configJson,
      String credentialRef,
      Instant now) {
    validateNoSecrets(configJson);
    this.name = Objects.requireNonNull(name, "name");
    this.handlerKey = Objects.requireNonNull(handlerKey, "handlerKey");
    this.status = Objects.requireNonNull(status, "status");
    this.configJson =
        configJson != null ? configJson.deepCopy() : JsonNodeFactory.instance.objectNode();
    this.credentialRef = credentialRef;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public static void validateKey(String key) {
    Objects.requireNonNull(key, "key");
    if (!key.matches("^[A-Z0-9_]{2,64}$")) {
      throw new IllegalArgumentException(
          "Connector key must be uppercase alphanumeric/underscore between 2 and 64 characters: "
              + key);
    }
  }

  public static void validateNoSecrets(JsonNode config) {
    if (config == null || !config.isObject()) return;
    config
        .fieldNames()
        .forEachRemaining(
            field -> {
              String lower = field.toLowerCase();
              if (lower.equals("connectorkey")
                  || lower.equals("actionkey")
                  || lower.equals("nodekey")
                  || lower.equals("formkey")
                  || lower.equals("handlerkey")) {
                return;
              }
              if (lower.contains("secret")
                  || lower.contains("password")
                  || lower.contains("token")
                  || (lower.contains("key") && !lower.contains("ref"))) {
                JsonNode val = config.get(field);
                if (val != null
                    && val.isTextual()
                    && !val.asText().startsWith("vault://")
                    && !val.asText().startsWith("secret:")) {
                  throw new IllegalArgumentException(
                      "Direct secret storage forbidden in connector config. Use credentialRef: "
                          + field);
                }
              }
            });
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

  public String getConnectorType() {
    return connectorType;
  }

  public String getHandlerKey() {
    return handlerKey;
  }

  public String getStatus() {
    return status;
  }

  public JsonNode getConfigJson() {
    return configJson != null ? configJson.deepCopy() : null;
  }

  public String getCredentialRef() {
    return credentialRef;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
