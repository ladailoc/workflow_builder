package com.fpt.workflow.slanotification.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record NotificationRequest(
    UUID eventId,
    UUID nodeExecutionId,
    UUID taskId,
    String channel,
    JsonNode participantConfig,
    UUID referenceUserId,
    JsonNode item,
    JsonNode templateSnapshot,
    JsonNode payload,
    String dedupKey,
    int maxAttempts,
    boolean allowAfterTerminal) {}
