package com.fpt.workflow.sla;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.shared.domain.*;
import com.fpt.workflow.sla.domain.SlaExecution;
import com.fpt.workflow.sla.repository.SlaExecutionRepository;
import com.fpt.workflow.sla.service.*;
import com.fpt.workflow.task.domain.*;
import com.fpt.workflow.task.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SlaActionExecutorTest {
  @Test
  void escalationReassignsWithHistoryAndAuditAndReplayIsSafe() {
    var slas = mock(SlaExecutionRepository.class);
    var tasks = mock(TaskExecutionRepository.class);
    var history = mock(TaskAssignmentHistoryRepository.class);
    var audits = mock(AuditEventRepository.class);
    var resolver = mock(EscalationParticipantResolver.class);
    Instant due = Instant.parse("2026-09-08T01:00:00Z"), now = due.plusSeconds(1);
    UUID oldUser = UUID.randomUUID(), newUser = UUID.randomUUID(), taskId = UUID.randomUUID();
    TaskExecution task =
        TaskExecution.create(
            taskId,
            UUID.randomUUID(),
            null,
            oldUser,
            "Task",
            null,
            null,
            JsonNodeFactory.instance.objectNode(),
            0,
            due,
            due.minusSeconds(60));
    SlaExecution sla =
        SlaExecution.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            task.getNodeExecutionId(),
            taskId,
            null,
            JsonNodeFactory.instance.objectNode(),
            due.minusSeconds(60),
            due,
            due);
    when(slas.findByIdForUpdate(sla.getId())).thenReturn(Optional.of(sla));
    when(tasks.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
    when(resolver.resolve(task, sla, now)).thenReturn(newUser);
    DefaultSlaActionExecutor executor =
        new DefaultSlaActionExecutor(
            slas, tasks, history, audits, resolver, UUID::randomUUID, () -> now);
    var first =
        executor.execute(
            sla.getId(), new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));
    var replay =
        executor.execute(
            sla.getId(), new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));
    assertThat(first.applied()).isTrue();
    assertThat(first.originalAssignee()).isEqualTo(oldUser);
    assertThat(first.newAssignee()).isEqualTo(newUser);
    assertThat(replay.applied()).isFalse();
    assertThat(task.getAssigneeId()).isEqualTo(newUser);
    verify(history)
        .save(
            argThat(
                h ->
                    h.getFromUserId().equals(oldUser)
                        && h.getToUserId().equals(newUser)
                        && h.getReason().equals("SLA_ESCALATION")));
    verify(audits)
        .save(
            argThat(
                a ->
                    a.getMetadataJson().path("originalAssignee").asText().equals(oldUser.toString())
                        && a.getMetadataJson()
                            .path("newAssignee")
                            .asText()
                            .equals(newUser.toString())));
  }

  @Test
  void executeDueProcessesAllDueSlas() {
    var slas = mock(SlaExecutionRepository.class);
    var tasks = mock(TaskExecutionRepository.class);
    var history = mock(TaskAssignmentHistoryRepository.class);
    var audits = mock(AuditEventRepository.class);
    var resolver = mock(EscalationParticipantResolver.class);
    Instant due = Instant.parse("2026-09-08T01:00:00Z"), now = due.plusSeconds(1);
    UUID oldUser = UUID.randomUUID(), newUser = UUID.randomUUID(), taskId = UUID.randomUUID();
    TaskExecution task =
        TaskExecution.create(
            taskId,
            UUID.randomUUID(),
            null,
            oldUser,
            "Task",
            null,
            null,
            JsonNodeFactory.instance.objectNode(),
            0,
            due,
            due.minusSeconds(60));
    SlaExecution sla =
        SlaExecution.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            task.getNodeExecutionId(),
            taskId,
            null,
            JsonNodeFactory.instance.objectNode(),
            due.minusSeconds(60),
            due,
            due);
    when(slas.findAllByStatusAndNextActionAtLessThanEqual("ACTIVE", now)).thenReturn(List.of(sla));
    when(slas.findByIdForUpdate(sla.getId())).thenReturn(Optional.of(sla));
    when(tasks.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
    when(resolver.resolve(task, sla, now)).thenReturn(newUser);

    DefaultSlaActionExecutor executor =
        new DefaultSlaActionExecutor(
            slas, tasks, history, audits, resolver, UUID::randomUUID, () -> now);

    var results =
        executor.executeDue(
            now, new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().applied()).isTrue();
    assertThat(results.getFirst().newAssignee()).isEqualTo(newUser);
  }
}
