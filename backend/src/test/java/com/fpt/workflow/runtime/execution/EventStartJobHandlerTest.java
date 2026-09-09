package com.fpt.workflow.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.operations.job.JobExecutionResult;
import com.fpt.workflow.operations.job.WorkflowJob;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventStartJobHandlerTest {

  @Test
  void startsEventFromDurableIdentityAndCompletesJob() {
    WorkflowExecutionService executionService = mock(WorkflowExecutionService.class);
    WorkflowJob job = mock(WorkflowJob.class);
    UUID eventId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();
    var payload =
        JsonNodeFactory.instance
            .objectNode()
            .put("eventId", eventId.toString())
            .put("cycleId", cycleId.toString())
            .put("correlationId", correlationId.toString())
            .put("commandId", commandId.toString());
    when(job.getPayloadJson()).thenReturn(payload);

    JobExecutionResult result = new EventStartJobHandler(executionService).execute(job);

    assertThat(result).isInstanceOf(JobExecutionResult.Success.class);
    verify(executionService)
        .startEvent(eventId, cycleId, new CorrelationId(correlationId), new CommandId(commandId));
  }
}
