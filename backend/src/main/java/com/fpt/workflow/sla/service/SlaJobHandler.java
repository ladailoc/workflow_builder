package com.fpt.workflow.sla.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.operations.job.JobExecutionResult;
import com.fpt.workflow.operations.job.WorkflowJob;
import com.fpt.workflow.operations.job.WorkflowJobHandler;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SlaJobHandler implements WorkflowJobHandler {

  private final SlaActionExecutor executor;

  public SlaJobHandler(SlaActionExecutor executor) {
    this.executor = executor;
  }

  @Override
  public String jobType() {
    return "SLA_ACTION";
  }

  @Override
  public JobExecutionResult execute(WorkflowJob job) {
    String slaIdStr = job.getPayloadJson().path("slaExecutionId").asText();
    if (slaIdStr == null || slaIdStr.isBlank()) {
      return JobExecutionResult.dead(
          JsonNodeFactory.instance.objectNode().put("error", "Missing slaExecutionId in payload"));
    }
    UUID slaId = UUID.fromString(slaIdStr);
    CorrelationId correlationId = new CorrelationId(UUID.randomUUID());
    CommandId commandId = new CommandId(UUID.randomUUID());
    SlaActionExecutor.ExecutionResult result = executor.execute(slaId, correlationId, commandId);
    return JobExecutionResult.success();
  }
}
