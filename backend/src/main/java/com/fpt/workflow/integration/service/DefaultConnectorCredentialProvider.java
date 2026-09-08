package com.fpt.workflow.integration.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class DefaultConnectorCredentialProvider implements ConnectorCredentialProvider {

  private final Map<String, String> secrets = new ConcurrentHashMap<>();

  @Override
  public Optional<String> getSigningSecret(String connectorKey, String credentialRef) {
    if (credentialRef != null && secrets.containsKey(credentialRef)) {
      return Optional.of(secrets.get(credentialRef));
    }
    if (connectorKey != null && secrets.containsKey(connectorKey)) {
      return Optional.of(secrets.get(connectorKey));
    }
    return Optional.empty();
  }

  @Override
  public void registerSecret(String keyOrRef, String secret) {
    if (keyOrRef != null && secret != null) {
      secrets.put(keyOrRef, secret);
    }
  }
}
