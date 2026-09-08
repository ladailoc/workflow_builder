package com.fpt.workflow.connector.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "connector_actions",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"connector_id", "action_key"})})
public class ConnectorAction {

  @Id private UUID id;

  @Column(name = "connector_id", nullable = false)
  private UUID connectorId;

  @Column(name = "action_key", nullable = false, length = 64)
  private String actionKey;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected ConnectorAction() {}

  public static ConnectorAction create(
      UUID id, UUID connectorId, String actionKey, String name, String status, Instant now) {
    validateActionKey(actionKey);
    ConnectorAction action = new ConnectorAction();
    action.id = Objects.requireNonNull(id, "id");
    action.connectorId = Objects.requireNonNull(connectorId, "connectorId");
    action.actionKey = actionKey;
    action.name = Objects.requireNonNull(name, "name");
    action.status = status != null ? status : "ACTIVE";
    action.createdAt = Objects.requireNonNull(now, "now");
    action.updatedAt = now;
    return action;
  }

  public void update(String name, String status, Instant now) {
    this.name = Objects.requireNonNull(name, "name");
    this.status = Objects.requireNonNull(status, "status");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public static void validateActionKey(String key) {
    Objects.requireNonNull(key, "actionKey");
    if (!key.matches("^[A-Z0-9_]{2,64}$")) {
      throw new IllegalArgumentException(
          "Action key must be uppercase alphanumeric/underscore between 2 and 64 characters: "
              + key);
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getConnectorId() {
    return connectorId;
  }

  public String getActionKey() {
    return actionKey;
  }

  public String getName() {
    return name;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
