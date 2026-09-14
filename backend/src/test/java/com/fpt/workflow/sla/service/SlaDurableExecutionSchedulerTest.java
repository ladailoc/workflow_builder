package com.fpt.workflow.sla.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.operations.job.JobExecutionResult;
import com.fpt.workflow.operations.job.WorkflowJob;
import com.fpt.workflow.operations.job.WorkflowJobTransactions;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.sla.domain.SlaExecution;
import com.fpt.workflow.sla.repository.BusinessCalendarHolidayRepository;
import com.fpt.workflow.sla.repository.BusinessCalendarHourRepository;
import com.fpt.workflow.sla.repository.BusinessCalendarRepository;
import com.fpt.workflow.sla.repository.SlaExecutionRepository;
import com.fpt.workflow.task.service.TaskSlaActivationPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SlaDurableExecutionSchedulerTest {

  private SlaActionExecutor actionExecutor;
  private PlatformClock clock;
  private final Instant now = Instant.parse("2026-03-30T12:00:00Z");

  @BeforeEach
  void setUp() {
    actionExecutor = mock(SlaActionExecutor.class);
    clock = () -> now;
  }

  @Test
  @DisplayName("SlaJobHandler processes SLA_ACTION job and invokes executor")
  void slaJobHandlerExecutesDurableJob() {
    SlaJobHandler handler = new SlaJobHandler(actionExecutor);
    assertThat(handler.jobType()).isEqualTo("SLA_ACTION");

    UUID slaId = UUID.randomUUID();
    WorkflowJob job = mock(WorkflowJob.class);
    ObjectNode payload = JsonNodeFactory.instance.objectNode().put("slaExecutionId", slaId.toString());
    when(job.getPayloadJson()).thenReturn(payload);

    when(actionExecutor.execute(eq(slaId), any(CorrelationId.class), any(CommandId.class)))
        .thenReturn(new SlaActionExecutor.ExecutionResult(true, UUID.randomUUID(), UUID.randomUUID(), "BREACHED"));

    JobExecutionResult result = handler.execute(job);
    assertThat(result).isInstanceOf(JobExecutionResult.Success.class);
    verify(actionExecutor).execute(eq(slaId), any(CorrelationId.class), any(CommandId.class));
  }

  @Test
  @DisplayName("SlaExecutionPoller invokes executeDue with current timestamp")
  void slaExecutionPollerPollsDueSlas() {
    SlaExecutionPoller poller = new SlaExecutionPoller(actionExecutor, clock);

    when(actionExecutor.executeDue(eq(now), any(CorrelationId.class), any(CommandId.class)))
        .thenReturn(List.of());

    poller.pollDueSlas();

    verify(actionExecutor).executeDue(eq(now), any(CorrelationId.class), any(CommandId.class));
  }

  @Test
  @DisplayName("SlaActivationService enqueues SLA_ACTION job to workflow_jobs on record")
  void slaActivationServiceEnqueuesJobOnRecord() {
    BusinessCalendarRepository calendars = mock(BusinessCalendarRepository.class);
    BusinessCalendarHourRepository hours = mock(BusinessCalendarHourRepository.class);
    BusinessCalendarHolidayRepository holidays = mock(BusinessCalendarHolidayRepository.class);
    SlaExecutionRepository executions = mock(SlaExecutionRepository.class);
    BusinessTimeCalculator calculator = mock(BusinessTimeCalculator.class);
    UuidGenerator uuids = UUID::randomUUID;
    WorkflowJobTransactions jobs = mock(WorkflowJobTransactions.class);

    SlaActivationService service =
        new SlaActivationService(calendars, hours, holidays, executions, calculator, uuids, jobs);

    UUID eventId = UUID.randomUUID();
    UUID nodeExecutionId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    Instant dueAt = now.plusSeconds(3600);
    Instant nextActionAt = dueAt;

    TaskSlaActivationPort.SlaPlan plan =
        new TaskSlaActivationPort.SlaPlan(null, JsonNodeFactory.instance.objectNode(), dueAt, nextActionAt);

    service.record(eventId, nodeExecutionId, taskId, now, plan);

    verify(executions).save(any(SlaExecution.class));

    ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> aggTypeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<UUID> aggIdCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<Instant> nextRunCaptor = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<String> dedupCaptor = ArgumentCaptor.forClass(String.class);

    verify(jobs).enqueue(
        typeCaptor.capture(),
        aggTypeCaptor.capture(),
        aggIdCaptor.capture(),
        any(),
        eq(3),
        nextRunCaptor.capture(),
        dedupCaptor.capture());

    assertThat(typeCaptor.getValue()).isEqualTo("SLA_ACTION");
    assertThat(aggTypeCaptor.getValue()).isEqualTo("SLA");
    assertThat(nextRunCaptor.getValue()).isEqualTo(nextActionAt);
    assertThat(dedupCaptor.getValue()).startsWith("sla:");
  }
}
