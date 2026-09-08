package com.fpt.workflow.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import java.time.Instant;
import java.util.*;

/** Task-owned port; SLA infrastructure implements it without creating a task -> SLA dependency. */
public interface TaskSlaActivationPort {
  Optional<SlaPlan> plan(NodeDefinition node, Instant activatedAt);

  void record(UUID eventId, UUID nodeExecutionId, UUID taskId, Instant startedAt, SlaPlan plan);

  void complete(UUID taskId, Instant completedAt);

  void cancel(UUID taskId, Instant cancelledAt);

  record SlaPlan(UUID calendarId, JsonNode configSnapshot, Instant dueAt, Instant nextActionAt) {}
}
