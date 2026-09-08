package com.fpt.workflow.runtime.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.task.service.DefaultActiveTaskCancellationPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

class EventLifecycleServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper();

  private EventRepository eventRepository;
  private NodeExecutionRepository nodeExecutionRepository;
  private NodeDefinitionRepository nodeRepository;
  private NodeTypeRegistry registry;
  private TaskExecutionRepository taskRepository;
  private AuditEventRepository auditRepository;
  private ActorContextProvider actorProvider;
  private EventLifecycleService service;

  private UUID eventId;
  private UUID ticketId;
  private UUID versionId;
  private UUID actorId;
  private Event event;

  @BeforeEach
  void setUp() {
    eventRepository = mock(EventRepository.class);
    nodeExecutionRepository = mock(NodeExecutionRepository.class);
    nodeRepository = mock(NodeDefinitionRepository.class);
    registry = mock(NodeTypeRegistry.class);
    taskRepository = mock(TaskExecutionRepository.class);
    auditRepository = mock(AuditEventRepository.class);
    actorProvider = mock(ActorContextProvider.class);

    eventId = UUID.randomUUID();
    ticketId = UUID.randomUUID();
    versionId = UUID.randomUUID();
    actorId = UUID.randomUUID();

    event =
        Event.createRoot(
            eventId,
            ticketId,
            versionId,
            UUID.randomUUID(),
            null,
            null,
            "TICKET_SUBMIT",
            "corr-1",
            mapper.createObjectNode(),
            actorId,
            NOW);

    when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
    when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
    when(actorProvider.requireActor())
        .thenReturn(new ActorContext(actorId, "testUser", Set.of(RoleKey.USER), Set.of()));

    ActiveTaskCancellationPort cancellationPort =
        new DefaultActiveTaskCancellationPort(taskRepository);
    service =
        new EventLifecycleService(
            eventRepository,
            nodeExecutionRepository,
            nodeRepository,
            registry,
            cancellationPort,
            auditRepository,
            actorProvider,
            UUID::randomUUID,
            () -> NOW,
            mapper);
  }

  @Test
  void waitingOnlyIfNoRunnableBranchAndAtLeastOneWaitExists() {
    event.markRunning();

    NodeExecution waitingNode = nodeExecution("wait-key", 0);
    waitingNode.markReady();
    waitingNode.start(NOW);
    waitingNode.waitFor(RuntimeWaitReason.HUMAN_TASK);

    when(nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(eventId))
        .thenReturn(List.of(waitingNode));

    EventStatus result = service.syncEventStatus(eventId);

    assertThat(result).isEqualTo(EventStatus.WAITING);
    assertThat(event.getStatus()).isEqualTo(EventStatus.WAITING);
    assertThat(event.getWaitReason()).isEqualTo(RuntimeWaitReason.HUMAN_TASK);
  }

  @Test
  void runningIfAtLeastOneBranchIsRunningEvenIfAnotherIsWaiting() {
    event.markRunning();

    NodeExecution waitingNode = nodeExecution("wait-key", 0);
    waitingNode.markReady();
    waitingNode.start(NOW);
    waitingNode.waitFor(RuntimeWaitReason.HUMAN_TASK);

    NodeExecution runningNode = nodeExecution("run-key", 1);
    runningNode.markReady();
    runningNode.start(NOW);

    when(nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(eventId))
        .thenReturn(List.of(waitingNode, runningNode));

    EventStatus result = service.syncEventStatus(eventId);

    assertThat(result).isEqualTo(EventStatus.RUNNING);
    assertThat(event.getStatus()).isEqualTo(EventStatus.RUNNING);
  }

  @Test
  void cancelsActiveNodesAndTasksWhileKeepingCompletedTasksIntact() {
    event.markRunning();

    NodeExecution activeNode = nodeExecution("active-key", 0);
    activeNode.markReady();
    activeNode.start(NOW);

    NodeExecution completedNode = nodeExecution("done-key", 1);
    completedNode.markReady();
    completedNode.start(NOW);
    completedNode.complete("COMPLETED", mapper.createObjectNode(), NOW);

    when(nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(eventId))
        .thenReturn(List.of(activeNode, completedNode));

    TaskExecution activeTask =
        TaskExecution.create(
            UUID.randomUUID(),
            activeNode.getId(),
            null,
            actorId,
            "Approval Task",
            "Please review",
            null,
            mapper.createObjectNode(),
            50,
            null,
            NOW);

    TaskExecution completedTask =
        TaskExecution.create(
            UUID.randomUUID(),
            completedNode.getId(),
            null,
            actorId,
            "Completed Task",
            "Done",
            null,
            mapper.createObjectNode(),
            50,
            null,
            NOW);
    completedTask.claim(actorId);
    completedTask.complete(new BusinessOutcome("APPROVED"), NOW);

    when(taskRepository.findAllByNodeExecutionIdOrderByCreatedAtAsc(activeNode.getId()))
        .thenReturn(List.of(activeTask));
    when(taskRepository.findAllByNodeExecutionIdOrderByCreatedAtAsc(completedNode.getId()))
        .thenReturn(List.of(completedTask));

    CommandId cmd = new CommandId(UUID.randomUUID());
    CorrelationId corr = new CorrelationId(UUID.randomUUID());
    Event cancelled = service.cancelEvent(eventId, cmd, corr, "User cancelled");

    assertThat(cancelled.getStatus()).isEqualTo(EventStatus.CANCELLED);
    assertThat(cancelled.getOutcome()).isEqualTo("CANCELLED");
    assertThat(activeNode.getStatus()).isEqualTo(NodeExecutionStatus.CANCELLED);
    assertThat(completedNode.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);

    // Active task was cancelled
    assertThat(activeTask.getStatus()).isEqualTo(TaskStatus.CANCELLED);

    // Completed task remains historically completed!
    assertThat(completedTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(completedTask.getOutcome()).isEqualTo("APPROVED");

    verify(auditRepository).save(any(AuditEvent.class));
  }

  @Test
  void cancelVsCompletionRaceTerminalWins() {
    event.markRunning();
    event.complete("APPROVED", NOW);

    CommandId cmd = new CommandId(UUID.randomUUID());
    CorrelationId corr = new CorrelationId(UUID.randomUUID());

    // Once completed, cancel must fail (Terminal wins)
    assertThatThrownBy(() -> service.cancelEvent(eventId, cmd, corr, "Cancel request"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot cancel terminal Event");
  }

  @Test
  void lateCommandRejectedOnTerminalEvent() {
    event.markRunning();
    event.cancel("CANCELLED", NOW);

    // Provide ADMIN role so permission check passes and terminal state check is exercised
    when(actorProvider.requireActor())
        .thenReturn(new ActorContext(actorId, "adminUser", Set.of(RoleKey.ADMIN), Set.of()));

    // Any late termination or sync on terminal event rejects transition
    CommandId cmd = new CommandId(UUID.randomUUID());
    CorrelationId corr = new CorrelationId(UUID.randomUUID());

    assertThatThrownBy(() -> service.terminateEvent(eventId, cmd, corr, "Admin terminate"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot terminate terminal Event");
  }

  @Test
  void terminateRequiresMandatoryReason() {
    event.markRunning();
    CommandId cmd = new CommandId(UUID.randomUUID());
    CorrelationId corr = new CorrelationId(UUID.randomUUID());

    assertThatThrownBy(() -> service.terminateEvent(eventId, cmd, corr, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mandatory");

    assertThatThrownBy(() -> service.terminateEvent(eventId, cmd, corr, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mandatory");
  }

  @Test
  void terminateRestrictedToAdminOrOperator() {
    event.markRunning();
    CommandId cmd = new CommandId(UUID.randomUUID());
    CorrelationId corr = new CorrelationId(UUID.randomUUID());

    // Normal USER role is denied
    when(actorProvider.requireActor())
        .thenReturn(new ActorContext(actorId, "normalUser", Set.of(RoleKey.USER), Set.of()));

    assertThatThrownBy(() -> service.terminateEvent(eventId, cmd, corr, "Emergency shutdown"))
        .isInstanceOf(AccessDeniedException.class);

    // ADMIN role is allowed
    when(actorProvider.requireActor())
        .thenReturn(new ActorContext(actorId, "adminUser", Set.of(RoleKey.ADMIN), Set.of()));

    Event terminated = service.terminateEvent(eventId, cmd, corr, "Emergency shutdown");
    assertThat(terminated.getStatus()).isEqualTo(EventStatus.TERMINATED);
    assertThat(terminated.getEndedAt()).isNotNull();
  }

  @Test
  void restartCreatesNewEventAndPreservesRelationWithoutReopeningTerminalEvent() {
    event.markRunning();
    event.fail(NOW);

    CommandId cmd = new CommandId(UUID.randomUUID());
    CorrelationId corr = new CorrelationId(UUID.randomUUID());

    Event restarted = service.restartEvent(eventId, cmd, corr, null);

    assertThat(restarted.getId()).isNotEqualTo(event.getId());
    assertThat(restarted.getStatus()).isEqualTo(EventStatus.CREATED);
    assertThat(restarted.getPreviousEventId()).isEqualTo(event.getId());
    assertThat(restarted.getRestartedFromEventId()).isEqualTo(event.getId());
    assertThat(restarted.getTicketId()).isEqualTo(ticketId);
    assertThat(restarted.getTriggerType()).isEqualTo("RESTART");

    // The original event remains strictly FAILED (never reopened)
    assertThat(event.getStatus()).isEqualTo(EventStatus.FAILED);

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getEventType()).isEqualTo("EVENT_RESTARTED");
  }

  @Test
  void restartRejectsNonTerminalEvent() {
    event.markRunning();

    CommandId cmd = new CommandId(UUID.randomUUID());
    CorrelationId corr = new CorrelationId(UUID.randomUUID());

    assertThatThrownBy(() -> service.restartEvent(eventId, cmd, corr, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Restart requires a terminal Event");
  }

  private NodeExecution nodeExecution(String activationKey, int iteration) {
    return NodeExecution.create(
        UUID.randomUUID(),
        eventId,
        UUID.randomUUID(),
        activationKey,
        UUID.randomUUID(),
        iteration,
        "root",
        null,
        null,
        null,
        mapper.createObjectNode(),
        UUID.randomUUID(),
        NOW);
  }
}
