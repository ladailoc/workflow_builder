package com.fpt.workflow.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public final class TaskDtos {

  private TaskDtos() {}

  public record TaskItemView(
      UUID id,
      UUID nodeExecutionId,
      UUID ticketId,
      UUID eventId,
      String title,
      String description,
      TaskStatus status,
      String outcome,
      int priority,
      UUID assigneeId,
      Instant dueAt,
      Instant createdAt,
      Instant completedAt,
      long lockVersion,
      JsonNode formSchemaJson,
      JsonNode inputSnapshotJson) {}

  public record TaskActionRequest(
      String comment, UUID targetUserId, JsonNode formData, JsonNode requestedFields) {}
}
