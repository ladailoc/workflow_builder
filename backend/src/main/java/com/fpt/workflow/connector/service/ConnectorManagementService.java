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
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorManagementService {

  private final ConnectorDefinitionRepository connectorRepository;
  private final ConnectorActionRepository actionRepository;
  private final ConnectorActionVersionRepository versionRepository;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;

  @Autowired
  public ConnectorManagementService(
      ConnectorDefinitionRepository connectorRepository,
      ConnectorActionRepository actionRepository,
      ConnectorActionVersionRepository versionRepository,
      UuidGenerator uuidGenerator,
      PlatformClock clock) {
    this.connectorRepository = connectorRepository;
    this.actionRepository = actionRepository;
    this.versionRepository = versionRepository;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
  }

  @Transactional
  public ConnectorDefinition registerConnector(
      String key,
      String name,
      String connectorType,
      String handlerKey,
      JsonNode configJson,
      String credentialRef,
      ActorContext actor) {
    requireTechnicalAdmin(actor);
    if (connectorRepository.existsByKey(key)) {
      throw new IllegalStateException("Connector key already exists: " + key);
    }
    Instant now = clock.now();
    ConnectorDefinition connector =
        ConnectorDefinition.create(
            uuidGenerator.generate(),
            key,
            name,
            connectorType,
            handlerKey,
            "ACTIVE",
            configJson,
            credentialRef,
            now);
    return connectorRepository.save(connector);
  }

  @Transactional
  public ConnectorAction registerAction(
      String connectorKey, String actionKey, String name, ActorContext actor) {
    requireTechnicalAdmin(actor);
    ConnectorDefinition connector =
        connectorRepository
            .findByKey(connectorKey)
            .orElseThrow(
                () -> new IllegalArgumentException("Connector not found: " + connectorKey));

    if (actionRepository.findByConnectorIdAndActionKey(connector.getId(), actionKey).isPresent()) {
      throw new IllegalStateException(
          "Action " + actionKey + " already exists on connector " + connectorKey);
    }

    Instant now = clock.now();
    ConnectorAction action =
        ConnectorAction.create(
            uuidGenerator.generate(), connector.getId(), actionKey, name, "ACTIVE", now);
    return actionRepository.save(action);
  }

  @Transactional
  public ConnectorActionVersion publishActionVersion(
      String connectorKey,
      String actionKey,
      int versionNo,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode executionConfigJson,
      JsonNode retryPolicyJson,
      JsonNode idempotencyPolicyJson,
      JsonNode errorMappingJson,
      JsonNode permissionPolicyJson,
      ActorContext actor) {
    requireTechnicalAdmin(actor);

    ConnectorDefinition connector =
        connectorRepository
            .findByKey(connectorKey)
            .orElseThrow(
                () -> new IllegalArgumentException("Connector not found: " + connectorKey));

    ConnectorAction action =
        actionRepository
            .findByConnectorIdAndActionKey(connector.getId(), actionKey)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Action " + actionKey + " not found on connector " + connectorKey));

    if (versionRepository
        .findByConnectorActionIdAndVersionNo(action.getId(), versionNo)
        .isPresent()) {
      throw new IllegalStateException(
          "Action version "
              + connectorKey
              + "/"
              + actionKey
              + " v"
              + versionNo
              + " already exists and is immutable");
    }

    Instant now = clock.now();
    ConnectorActionVersion version =
        ConnectorActionVersion.publish(
            uuidGenerator.generate(),
            action.getId(),
            versionNo,
            inputSchemaJson,
            outputSchemaJson,
            executionConfigJson,
            retryPolicyJson,
            idempotencyPolicyJson,
            errorMappingJson,
            permissionPolicyJson,
            now);
    return versionRepository.save(version);
  }

  private void requireTechnicalAdmin(ActorContext actor) {
    Objects.requireNonNull(actor, "actor");
    if (!actor.hasRole(RoleKey.ADMIN) && !actor.hasRole(RoleKey.OPERATOR)) {
      throw new AccessDeniedException(
          "Actor " + actor.actorId() + " does not have technical admin permissions");
    }
  }
}
