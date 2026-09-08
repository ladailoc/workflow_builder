package com.fpt.workflow.integration.service;

import java.util.Optional;

public interface ConnectorCredentialProvider {

  Optional<String> getSigningSecret(String connectorKey, String credentialRef);

  void registerSecret(String keyOrRef, String secret);
}
