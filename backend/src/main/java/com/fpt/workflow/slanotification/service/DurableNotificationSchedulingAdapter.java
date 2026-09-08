package com.fpt.workflow.slanotification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.nodetype.NotificationSchedulingPort;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class DurableNotificationSchedulingAdapter implements NotificationSchedulingPort {
  private final NotificationService notifications;
  private final NodeExecutionRepository nodeExecutions;

  public DurableNotificationSchedulingAdapter(
      NotificationService notifications, NodeExecutionRepository nodeExecutions) {
    this.notifications = notifications;
    this.nodeExecutions = nodeExecutions;
  }

  @Override
  public void schedule(
      UUID nodeExecutionId, JsonNode input, JsonNode configuration, String dedupKey) {
    var execution = nodeExecutions.findById(nodeExecutionId).orElseThrow();
    notifications.dispatch(
        new NotificationRequest(
            execution.getEventId(),
            nodeExecutionId,
            null,
            configuration.path("channel").asText(),
            configuration.path("participant"),
            null,
            null,
            configuration.path("template"),
            input,
            dedupKey,
            configuration.path("maxAttempts").asInt(3),
            configuration.path("allowAfterTerminal").asBoolean(true)));
  }
}
