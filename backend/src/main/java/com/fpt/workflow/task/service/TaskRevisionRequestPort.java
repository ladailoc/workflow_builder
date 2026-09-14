package com.fpt.workflow.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.util.List;
import java.util.UUID;

/** Task-owned port for opening a revision request without coupling the task feature to rework. */
public interface TaskRevisionRequestPort {

  void open(
      UUID taskId,
      UUID targetNodeId,
      String comment,
      List<FieldSpec> requestedFields,
      CorrelationId correlationId,
      CommandId commandId);

  record FieldSpec(
      String key,
      String label,
      String type,
      boolean required,
      boolean sensitive,
      JsonNode schema) {}
}
