package com.fpt.workflow.connector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.connector.domain.ConnectorActionVersion;
import com.fpt.workflow.security.ActorContext;
import java.util.List;

public interface ConnectorRegistry {

  ConnectorActionVersion requireActionVersion(String connectorKey, String actionKey, int versionNo);

  List<ConnectorActionVersion> findAvailableActionVersions(
      String connectorKey, String actionKey, ActorContext actor);

  void validateActionPermission(ConnectorActionVersion version, ActorContext actor);

  void validateNodeBinding(JsonNode nodeConfig, ActorContext actor);
}
