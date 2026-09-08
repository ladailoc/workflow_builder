package com.fpt.workflow.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record IntegrationCallRequest(
    UUID executionId,
    String connectorKey,
    String actionKey,
    int actionVersion,
    String idempotencyKey,
    JsonNode inputData,
    String credentialRef,
    JsonNode executionConfig) {}
