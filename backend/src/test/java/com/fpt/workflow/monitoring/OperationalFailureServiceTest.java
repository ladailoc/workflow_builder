package com.fpt.workflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.operations.job.WorkflowJobRepository;
import com.fpt.workflow.operations.job.WorkflowJobStatus;
import com.fpt.workflow.operations.outbox.OutboxEventRepository;
import com.fpt.workflow.operations.outbox.OutboxStatus;
import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.slanotification.domain.NotificationDispatchStatus;
import com.fpt.workflow.slanotification.repository.NotificationDispatchRepository;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationalFailureServiceTest {

  @Test
  void queriesEveryOperationalTerminalFailureBucketWithoutPayloadExposure() {
    WorkflowJobRepository jobs = mock(WorkflowJobRepository.class);
    OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    NotificationDispatchRepository notifications = mock(NotificationDispatchRepository.class);
    IntegrationExecutionRepository integrations = mock(IntegrationExecutionRepository.class);
    EventRepository events = mock(EventRepository.class);
    NodeExecutionRepository nodes = mock(NodeExecutionRepository.class);
    var service =
        new OperationalFailureService(
            jobs, outbox, notifications, integrations, events, nodes, new ObjectMapper());

    assertThat(service.list()).isEmpty();

    verify(jobs).findAllByStatusOrderByUpdatedAtAsc(WorkflowJobStatus.DEAD);
    verify(outbox).findAllByStatusOrderByCreatedAtAsc(OutboxStatus.DEAD);
    verify(notifications).findAllByStatusOrderByUpdatedAtAsc(NotificationDispatchStatus.DEAD);
    verify(integrations).findAllByStatusOrderByUpdatedAtAsc(IntegrationExecutionStatus.FAILED);
    verify(integrations)
        .findAllByStatusOrderByUpdatedAtAsc(IntegrationExecutionStatus.MANUAL_RECONCILIATION);
    verify(events).findAllByStatusOrderByStartedAtAsc(EventStatus.FAILED);
    verify(nodes).findAllByStatusOrderByCreatedAtAsc(NodeExecutionStatus.FAILED);
  }

  @Test
  void exposesInactiveActiveTaskAssigneeAsOperationalAttention() {
    WorkflowJobRepository jobs = mock(WorkflowJobRepository.class);
    OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    NotificationDispatchRepository notifications = mock(NotificationDispatchRepository.class);
    IntegrationExecutionRepository integrations = mock(IntegrationExecutionRepository.class);
    EventRepository events = mock(EventRepository.class);
    NodeExecutionRepository nodes = mock(NodeExecutionRepository.class);
    TaskExecutionRepository tasks = mock(TaskExecutionRepository.class);
    EmployeeRepository employees = mock(EmployeeRepository.class);
    Instant now = Instant.parse("2026-09-12T06:00:00Z");
    UUID assigneeId = UUID.randomUUID();
    TaskExecution task =
        TaskExecution.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            assigneeId,
            "Review",
            null,
            null,
            JsonNodeFactory.instance.objectNode(),
            50,
            null,
            now);
    Employee inactive =
        Employee.create(
            UUID.randomUUID(),
            assigneeId,
            "EMP-2",
            "Inactive Assignee",
            "inactive@example.test",
            now.minusSeconds(60));
    inactive.deactivate(now);

    when(tasks.findAllByStatusOrderByCreatedAtDesc(TaskStatus.READY)).thenReturn(List.of(task));
    when(employees.findByUserId(assigneeId)).thenReturn(Optional.of(inactive));
    OperationalFailureService service =
        new OperationalFailureService(
            jobs,
            outbox,
            notifications,
            integrations,
            events,
            nodes,
            new ObjectMapper(),
            tasks,
            employees);

    assertThat(service.list())
        .anySatisfy(
            failure -> {
              assertThat(failure.id()).isEqualTo(task.getId());
              assertThat(failure.kind()).isEqualTo("ASSIGNEE_INACTIVE");
              assertThat(failure.status()).isEqualTo("ATTENTION");
              assertThat(failure.error().path("requiresExplicitReassignment").asBoolean()).isTrue();
            });
  }
}
