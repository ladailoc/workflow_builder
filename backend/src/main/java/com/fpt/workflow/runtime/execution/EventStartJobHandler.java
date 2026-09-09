package com.fpt.workflow.runtime.execution;

import com.fpt.workflow.operations.job.JobExecutionResult;
import com.fpt.workflow.operations.job.WorkflowJob;
import com.fpt.workflow.operations.job.WorkflowJobHandler;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Durable, idempotent entry point for starting an Event after Ticket submission commits. */
@Component
public class EventStartJobHandler implements WorkflowJobHandler {

  private final WorkflowExecutionService executionService;

  public EventStartJobHandler(WorkflowExecutionService executionService) {
    this.executionService = executionService;
  }

  @Override
  public String jobType() {
    return "EVENT_START";
  }

  @Override
  public JobExecutionResult execute(WorkflowJob job) {
    var payload = job.getPayloadJson();
    executionService.startEvent(
        UUID.fromString(payload.path("eventId").asText()),
        UUID.fromString(payload.path("cycleId").asText()),
        new CorrelationId(UUID.fromString(payload.path("correlationId").asText())),
        new CommandId(UUID.fromString(payload.path("commandId").asText())));
    return JobExecutionResult.success();
  }
}
