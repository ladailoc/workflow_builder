package com.fpt.workflow.security.visibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.authorization.DefaultVisibilityResolver;
import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.task.repository.TaskAssignmentHistoryRepository;
import com.fpt.workflow.task.repository.TaskCandidateRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.task.domain.TaskAssignmentHistory;
import com.fpt.workflow.task.domain.TaskCandidate;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultVisibilityResolverTest {
  private final TicketRepository tickets = mock(TicketRepository.class);
  private final EventRepository events = mock(EventRepository.class);
  private final RequestTypeRepository requestTypes = mock(RequestTypeRepository.class);
  private final WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
  private final WorkflowDefinitionRepository definitions =
      mock(WorkflowDefinitionRepository.class);
  private final ParticipantSnapshotRepository participants =
      mock(ParticipantSnapshotRepository.class);
  private final NodeExecutionRepository nodes = mock(NodeExecutionRepository.class);
  private final TaskExecutionRepository tasks = mock(TaskExecutionRepository.class);
  private final TaskCandidateRepository candidates = mock(TaskCandidateRepository.class);
  private final TaskAssignmentHistoryRepository assignments =
      mock(TaskAssignmentHistoryRepository.class);
  private DefaultVisibilityResolver resolver;
  private Ticket ticket;
  private Event event;
  private UUID creator;

  @BeforeEach
  void setUp() {
    resolver =
        new DefaultVisibilityResolver(
            tickets,
            events,
            requestTypes,
            versions,
            definitions,
            participants,
            nodes,
            tasks,
            candidates,
            assignments);
    creator = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-12T00:00:00Z");
    ticket =
        Ticket.createDraft(
            ticketId, UUID.randomUUID(), creator, JsonNodeFactory.instance.objectNode(), now);
    event =
        Event.createRoot(
            UUID.randomUUID(),
            ticketId,
            versionId,
            revisionId,
            null,
            null,
            "TEST",
            "visibility",
            JsonNodeFactory.instance.objectNode(),
            creator,
            now);
    when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
    when(events.findById(event.getId())).thenReturn(Optional.of(event));
    when(events.findAllByTicketIdOrderByStartedAtAsc(ticketId)).thenReturn(List.of(event));
    when(participants.findAllByEventIdOrderByResolvedAtAsc(event.getId())).thenReturn(List.of());
    when(nodes.findAllByEventIdOrderByCreatedAtAsc(event.getId())).thenReturn(List.of());
  }

  @Test
  void deniesAnUnrelatedAuthenticatedUser() {
    ActorContext unrelated = actor(UUID.randomUUID(), RoleKey.USER);

    assertThat(resolver.mayViewEvent(unrelated, event.getId())).isFalse();
    assertThat(resolver.mayViewTicket(unrelated, ticket.getId())).isFalse();
  }

  @Test
  void grantsCreatorAndPrivilegedOperator() {
    assertThat(resolver.resolveEvent(actor(creator, RoleKey.USER), event.getId()).subject())
        .isEqualTo(VisibilityResolver.VisibilitySubject.CREATOR);
    assertThat(resolver.resolveEvent(actor(UUID.randomUUID(), RoleKey.OPERATOR), event.getId()).subject())
        .isEqualTo(VisibilityResolver.VisibilitySubject.PRIVILEGED_OPERATOR);
  }

  @Test
  void grantsSnapshottedParticipant() {
    UUID participantId = UUID.randomUUID();
    ParticipantSnapshot snapshot = mock(ParticipantSnapshot.class);
    when(snapshot.getResolvedUserId()).thenReturn(participantId);
    when(participants.findAllByEventIdOrderByResolvedAtAsc(event.getId()))
        .thenReturn(List.of(snapshot));

    assertThat(resolver.resolveEvent(actor(participantId, RoleKey.USER), event.getId()).subject())
        .isEqualTo(VisibilityResolver.VisibilitySubject.PARTICIPANT);
  }

  @Test
  void grantsOnlyTheActualWorkflowOwnerNotEveryOwnerRoleHolder() {
    UUID definitionId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    WorkflowVersion version = mock(WorkflowVersion.class);
    WorkflowDefinition definition = mock(WorkflowDefinition.class);
    when(version.getDefinitionId()).thenReturn(definitionId);
    when(definition.getOwnerId()).thenReturn(ownerId);
    when(versions.findById(event.getWorkflowVersionId())).thenReturn(Optional.of(version));
    when(definitions.findById(definitionId)).thenReturn(Optional.of(definition));

    assertThat(resolver.mayViewEvent(actor(ownerId, RoleKey.WORKFLOW_OWNER), event.getId())).isTrue();
    assertThat(
            resolver.mayViewEvent(
                actor(UUID.randomUUID(), RoleKey.WORKFLOW_OWNER), event.getId()))
        .isFalse();
  }

  @Test
  void grantsCurrentCandidateAndPreviousTaskParticipants() {
    UUID assigneeId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    UUID previousAssigneeId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    NodeExecution node = mock(NodeExecution.class);
    TaskExecution task = mock(TaskExecution.class);
    TaskAssignmentHistory history = mock(TaskAssignmentHistory.class);
    when(node.getId()).thenReturn(nodeId);
    when(task.getId()).thenReturn(taskId);
    when(task.getAssigneeId()).thenReturn(assigneeId);
    when(history.getFromUserId()).thenReturn(previousAssigneeId);
    when(nodes.findAllByEventIdOrderByCreatedAtAsc(event.getId())).thenReturn(List.of(node));
    when(tasks.findAllByNodeExecutionIdInOrderByCreatedAtAsc(List.of(nodeId)))
        .thenReturn(List.of(task));
    when(candidates.findAllByTaskIdOrderByCreatedAtAsc(taskId))
        .thenReturn(
            List.of(
                TaskCandidate.create(
                    taskId,
                    candidateId,
                    "TEST",
                    JsonNodeFactory.instance.objectNode(),
                    Instant.parse("2026-09-12T00:00:00Z"))));
    when(assignments.findAllByTaskIdOrderByCreatedAtAsc(taskId)).thenReturn(List.of(history));

    assertThat(resolver.mayViewEvent(actor(assigneeId, RoleKey.USER), event.getId())).isTrue();
    assertThat(resolver.mayViewEvent(actor(candidateId, RoleKey.USER), event.getId())).isTrue();
    assertThat(resolver.mayViewEvent(actor(previousAssigneeId, RoleKey.USER), event.getId()))
        .isTrue();
  }

  private ActorContext actor(UUID id, RoleKey role) {
    return new ActorContext(id, id.toString(), Set.of(role), Set.of());
  }
}
