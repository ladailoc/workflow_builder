package com.fpt.workflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InactiveAssigneeMonitorTest {

  @Test
  void organizationChangeAfterTaskActivationEmitsOneAlertWithoutChangingTheAssignment() {
    TaskExecutionRepository tasks = mock(TaskExecutionRepository.class);
    EmployeeRepository employees = mock(EmployeeRepository.class);
    AuditEventRepository audits = mock(AuditEventRepository.class);
    Instant activatedAt = Instant.parse("2026-09-12T06:00:00Z");
    Instant deactivatedAt = activatedAt.plusSeconds(60);
    UUID assigneeId = UUID.randomUUID();
    Employee employee =
        Employee.create(
            UUID.randomUUID(),
            assigneeId,
            "EMP-1",
            "Former Assignee",
            "former@example.test",
            activatedAt.minusSeconds(60));
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
            activatedAt);
    employee.deactivate(deactivatedAt);

    when(tasks.findAllByStatusOrderByCreatedAtDesc(TaskStatus.READY)).thenReturn(List.of(task));
    when(tasks.findAllByStatusOrderByCreatedAtDesc(TaskStatus.CLAIMED)).thenReturn(List.of());
    when(tasks.findAllByStatusOrderByCreatedAtDesc(TaskStatus.IN_PROGRESS)).thenReturn(List.of());
    when(employees.findByUserId(assigneeId)).thenReturn(Optional.of(employee));
    when(audits.findAllByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(
            "TASK_EXECUTION", task.getId()))
        .thenReturn(List.of());

    InactiveAssigneeMonitor monitor =
        new InactiveAssigneeMonitor(tasks, employees, audits, () -> deactivatedAt);

    assertThat(monitor.detectInactiveAssignees()).isEqualTo(1);
    assertThat(task.getAssigneeId()).isEqualTo(assigneeId);
    assertThat(task.getStatus()).isEqualTo(TaskStatus.READY);
    verify(tasks, never()).save(task);

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audits).saveAndFlush(auditCaptor.capture());
    AuditEvent alert = auditCaptor.getValue();
    assertThat(alert.getEventType()).isEqualTo("ASSIGNEE_INACTIVE");
    assertThat(alert.getMetadataJson().path("requiresExplicitReassignment").asBoolean()).isTrue();
    assertThat(alert.getMetadataJson().path("assigneeId").asText())
        .isEqualTo(assigneeId.toString());
  }
}
