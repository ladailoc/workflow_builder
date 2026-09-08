package com.fpt.workflow.integration.service;

import com.fpt.workflow.operations.job.*;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SystemActionJobHandler implements WorkflowJobHandler {
  private final SystemActionExecutionService actions;

  public SystemActionJobHandler(SystemActionExecutionService actions) {
    this.actions = actions;
  }

  @Override
  public String jobType() {
    return "SYSTEM_ACTION_EXECUTE";
  }

  @Override
  public JobExecutionResult execute(WorkflowJob job) {
    UUID nodeId = UUID.fromString(job.getPayloadJson().path("nodeExecutionId").asText());
    CorrelationId correlationId =
        new CorrelationId(UUID.fromString(job.getPayloadJson().path("correlationId").asText()));
    CommandId commandId =
        new CommandId(UUID.fromString(job.getPayloadJson().path("commandId").asText()));
    SystemActionAttemptOutcome result =
        actions.executeDurableAttempt(nodeId, job.getAttempts(), correlationId, commandId);
    return result.terminal()
        ? JobExecutionResult.success()
        : JobExecutionResult.retry(result.retryDelay(), result.error());
  }
}
