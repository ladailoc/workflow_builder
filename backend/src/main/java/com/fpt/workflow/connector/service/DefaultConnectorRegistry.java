package com.fpt.workflow.connector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.connector.domain.ConnectorAction;
import com.fpt.workflow.connector.domain.ConnectorActionVersion;
import com.fpt.workflow.connector.domain.ConnectorDefinition;
import com.fpt.workflow.connector.repository.ConnectorActionRepository;
import com.fpt.workflow.connector.repository.ConnectorActionVersionRepository;
import com.fpt.workflow.connector.repository.ConnectorDefinitionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.RoleKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultConnectorRegistry implements ConnectorRegistry {

  private final ConnectorDefinitionRepository connectorRepository;
  private final ConnectorActionRepository actionRepository;
  private final ConnectorActionVersionRepository versionRepository;

  @Autowired
  public DefaultConnectorRegistry(
      ConnectorDefinitionRepository connectorRepository,
      ConnectorActionRepository actionRepository,
      ConnectorActionVersionRepository versionRepository) {
    this.connectorRepository = connectorRepository;
    this.actionRepository = actionRepository;
    this.versionRepository = versionRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public ConnectorActionVersion requireActionVersion(
      String connectorKey, String actionKey, int versionNo) {
    Objects.requireNonNull(connectorKey, "connectorKey");
    Objects.requireNonNull(actionKey, "actionKey");

    ConnectorDefinition connector =
        connectorRepository
            .findByKey(connectorKey)
            .orElseThrow(
                () -> new IllegalArgumentException("Connector not found: " + connectorKey));

    if (!"ACTIVE".equalsIgnoreCase(connector.getStatus())) {
      throw new IllegalStateException("Connector is not active: " + connectorKey);
    }

    ConnectorAction action =
        actionRepository
            .findByConnectorIdAndActionKey(connector.getId(), actionKey)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Action " + actionKey + " not found in connector " + connectorKey));

    if (!"ACTIVE".equalsIgnoreCase(action.getStatus())) {
      throw new IllegalStateException("Action is not active: " + actionKey);
    }

    ConnectorActionVersion version =
        versionRepository
            .findByConnectorActionIdAndVersionNo(action.getId(), versionNo)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Action version not found: "
                            + connectorKey
                            + "/"
                            + actionKey
                            + " v"
                            + versionNo));

    if (!"PUBLISHED".equalsIgnoreCase(version.getStatus())) {
      throw new IllegalStateException(
          "Action version "
              + connectorKey
              + "/"
              + actionKey
              + " v"
              + versionNo
              + " is "
              + version.getStatus());
    }

    return version;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConnectorActionVersion> findAvailableActionVersions(
      String connectorKey, String actionKey, ActorContext actor) {
    List<ConnectorActionVersion> published =
        versionRepository.findAllPublished(connectorKey, actionKey);
    List<ConnectorActionVersion> permitted = new ArrayList<>();
    for (ConnectorActionVersion v : published) {
      try {
        validateActionPermission(v, actor);
        permitted.add(v);
      } catch (AccessDeniedException ignored) {
        // Excluded from actor's catalog
      }
    }
    return List.copyOf(permitted);
  }

  @Override
  public void validateActionPermission(ConnectorActionVersion version, ActorContext actor) {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(actor, "actor");

    if (actor.hasRole(RoleKey.ADMIN)) {
      return;
    }

    JsonNode policy = version.getPermissionPolicyJson();
    if (policy == null || !policy.isObject() || policy.isEmpty()) {
      return;
    }

    boolean hasRule = false;
    boolean allowed = false;

    if (policy.hasNonNull("allowedRoles") && policy.path("allowedRoles").isArray()) {
      hasRule = true;
      for (JsonNode roleNode : policy.path("allowedRoles")) {
        if (roleNode.isTextual() && actor.hasRole(RoleKey.of(roleNode.asText()))) {
          allowed = true;
          break;
        }
      }
    }

    if (!allowed && policy.hasNonNull("allowedUsers") && policy.path("allowedUsers").isArray()) {
      hasRule = true;
      String actorIdStr = actor.actorId().toString();
      for (JsonNode userNode : policy.path("allowedUsers")) {
        if (userNode.isTextual() && userNode.asText().equalsIgnoreCase(actorIdStr)) {
          allowed = true;
          break;
        }
      }
    }

    if (hasRule && !allowed) {
      throw new AccessDeniedException(
          "Actor "
              + actor.actorId()
              + " is not permitted to use action version "
              + version.getVersionNo());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public void validateNodeBinding(JsonNode nodeConfig, ActorContext actor) {
    if (nodeConfig == null || !nodeConfig.isObject()) {
      throw new IllegalArgumentException("System action node config must be an object");
    }

    if (!nodeConfig.hasNonNull("connectorKey") || !nodeConfig.path("connectorKey").isTextual()) {
      throw new IllegalArgumentException("System action requires connectorKey");
    }
    if (!nodeConfig.hasNonNull("actionKey") || !nodeConfig.path("actionKey").isTextual()) {
      throw new IllegalArgumentException("System action requires actionKey");
    }
    if (!nodeConfig.hasNonNull("actionVersion") || !nodeConfig.path("actionVersion").isInt()) {
      throw new IllegalArgumentException("System action requires integer actionVersion");
    }

    ConnectorDefinition.validateNoSecrets(nodeConfig);

    String connectorKey = nodeConfig.path("connectorKey").asText();
    String actionKey = nodeConfig.path("actionKey").asText();
    int actionVersion = nodeConfig.path("actionVersion").asInt();

    ConnectorActionVersion version = requireActionVersion(connectorKey, actionKey, actionVersion);
    validateActionPermission(version, actor);
  }
}
