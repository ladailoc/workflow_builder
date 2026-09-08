package com.fpt.workflow.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.time.Instant;

public record CallbackCommand(
    String callbackCorrelationId,
    String connectorKey,
    String externalEventId,
    String signature,
    Instant timestamp,
    String rawPayload,
    JsonNode parsedPayload,
    CorrelationId correlationId,
    CommandId commandId) {}
