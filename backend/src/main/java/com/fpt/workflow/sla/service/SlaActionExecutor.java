package com.fpt.workflow.sla.service;

import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SlaActionExecutor {
  ExecutionResult execute(UUID slaExecutionId, CorrelationId correlationId, CommandId commandId);

  List<ExecutionResult> executeDue(Instant at, CorrelationId correlationId, CommandId commandId);

  record ExecutionResult(boolean applied, UUID originalAssignee, UUID newAssignee, String status) {}
}
