package com.fpt.workflow.authorization;

import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.security.visibility.VisibilityResolver;
import com.fpt.workflow.task.domain.TaskAssignmentHistory;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskAssignmentHistoryRepository;
import com.fpt.workflow.task.repository.TaskCandidateRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conservative default visibility policy from workflow_spec.md section 20.3. Kept in a
 * composition package so the security contract remains independent of feature persistence.
 */
@Service
public class DefaultVisibilityResolver implements VisibilityResolver {
  private final TicketRepository tickets;
  private final EventRepository events;
  private final RequestTypeRepository requestTypes;
  private final WorkflowVersionRepository versions;
  private final WorkflowDefinitionRepository definitions;
  private final ParticipantSnapshotRepository participants;
  private final NodeExecutionRepository nodes;
  private final TaskExecutionRepository tasks;
  private final TaskCandidateRepository candidates;
  private final TaskAssignmentHistoryRepository assignments;

  public DefaultVisibilityResolver(
      TicketRepository tickets,
      EventRepository events,
      RequestTypeRepository requestTypes,
      WorkflowVersionRepository versions,
      WorkflowDefinitionRepository definitions,
      ParticipantSnapshotRepository participants,
      NodeExecutionRepository nodes,
      TaskExecutionRepository tasks,
      TaskCandidateRepository candidates,
      TaskAssignmentHistoryRepository assignments) {
    this.tickets = tickets;
    this.events = events;
    this.requestTypes = requestTypes;
    this.versions = versions;
    this.definitions = definitions;
    this.participants = participants;
    this.nodes = nodes;
    this.tasks = tasks;
    this.candidates = candidates;
    this.assignments = assignments;
  }

  @Override
  @Transactional(readOnly = true)
  public VisibilityDecision resolveTicket(ActorContext actor, UUID ticketId) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(ticketId, "ticketId");
    Ticket ticket = tickets.findById(ticketId).orElse(null);
    if (ticket == null) return VisibilityDecision.deny();
    VisibilityDecision direct = directTicketDecision(actor, ticket);
    if (direct.allowed()) return direct;
    for (Event event : events.findAllByTicketIdOrderByStartedAtAsc(ticketId)) {
      VisibilityDecision eventDecision = eventSpecificDecision(actor, event);
      if (eventDecision.allowed()) return eventDecision;
    }
    return VisibilityDecision.deny();
  }

  @Override
  @Transactional(readOnly = true)
  public VisibilityDecision resolveEvent(ActorContext actor, UUID eventId) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(eventId, "eventId");
    Event event = events.findById(eventId).orElse(null);
    if (event == null) return VisibilityDecision.deny();
    if (isPrivileged(actor)) {
      return VisibilityDecision.allow(VisibilitySubject.PRIVILEGED_OPERATOR);
    }
    Ticket ticket = tickets.findById(event.getTicketId()).orElse(null);
    if (ticket == null) return VisibilityDecision.deny();
    if (ticket.getCreatorId().equals(actor.actorId())) {
      return VisibilityDecision.allow(VisibilitySubject.CREATOR);
    }
    return eventSpecificDecision(actor, event);
  }

  private VisibilityDecision directTicketDecision(ActorContext actor, Ticket ticket) {
    if (isPrivileged(actor)) {
      return VisibilityDecision.allow(VisibilitySubject.PRIVILEGED_OPERATOR);
    }
    if (ticket.getCreatorId().equals(actor.actorId())) {
      return VisibilityDecision.allow(VisibilitySubject.CREATOR);
    }
    boolean ownsDefinition =
        requestTypes
            .findById(ticket.getRequestTypeId())
            .map(RequestType::getWorkflowDefinitionId)
            .flatMap(definitions::findById)
            .filter(definition -> definition.getOwnerId().equals(actor.actorId()))
            .isPresent();
    return ownsDefinition
        ? VisibilityDecision.allow(VisibilitySubject.WORKFLOW_OWNER)
        : VisibilityDecision.deny();
  }

  private VisibilityDecision eventSpecificDecision(ActorContext actor, Event event) {
    boolean ownsDefinition =
        versions
            .findById(event.getWorkflowVersionId())
            .flatMap(version -> definitions.findById(version.getDefinitionId()))
            .filter(definition -> definition.getOwnerId().equals(actor.actorId()))
            .isPresent();
    if (ownsDefinition) {
      return VisibilityDecision.allow(VisibilitySubject.WORKFLOW_OWNER);
    }
    boolean snapshottedParticipant =
        participants.findAllByEventIdOrderByResolvedAtAsc(event.getId()).stream()
            .anyMatch(snapshot -> actor.actorId().equals(snapshot.getResolvedUserId()));
    if (snapshottedParticipant || isTaskParticipant(actor.actorId(), event.getId())) {
      return VisibilityDecision.allow(VisibilitySubject.PARTICIPANT);
    }
    return VisibilityDecision.deny();
  }

  private boolean isTaskParticipant(UUID actorId, UUID eventId) {
    List<UUID> nodeIds =
        nodes.findAllByEventIdOrderByCreatedAtAsc(eventId).stream().map(n -> n.getId()).toList();
    if (nodeIds.isEmpty()) return false;
    for (TaskExecution task : tasks.findAllByNodeExecutionIdInOrderByCreatedAtAsc(nodeIds)) {
      if (actorId.equals(task.getAssigneeId())) return true;
      if (candidates.findAllByTaskIdOrderByCreatedAtAsc(task.getId()).stream()
          .anyMatch(candidate -> actorId.equals(candidate.getUserId()))) return true;
      for (TaskAssignmentHistory assignment :
          assignments.findAllByTaskIdOrderByCreatedAtAsc(task.getId())) {
        if (actorId.equals(assignment.getFromUserId())
            || actorId.equals(assignment.getToUserId())) return true;
      }
    }
    return false;
  }

  private boolean isPrivileged(ActorContext actor) {
    return actor.hasRole(RoleKey.ADMIN) || actor.hasRole(RoleKey.OPERATOR);
  }
}
