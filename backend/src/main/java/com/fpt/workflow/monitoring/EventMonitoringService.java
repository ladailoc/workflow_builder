package com.fpt.workflow.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.context.EventContextBuilder;
import com.fpt.workflow.runtime.domain.*;
import com.fpt.workflow.runtime.repository.*;
import com.fpt.workflow.runtime.routing.domain.RoutingDecision;
import com.fpt.workflow.runtime.routing.repository.RoutingDecisionRepository;
import com.fpt.workflow.security.*;
import com.fpt.workflow.task.domain.*;
import com.fpt.workflow.task.repository.*;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, occurrence-aware monitoring projection. It never exposes unmasked runtime context. */
@Service
public class EventMonitoringService {
  private final EventRepository events;
  private final WorkflowVersionRepository versions;
  private final NodeExecutionRepository nodes;
  private final TaskExecutionRepository tasks;
  private final ParticipantSnapshotRepository participants;
  private final TaskAssignmentHistoryRepository assignments;
  private final RoutingDecisionRepository routes;
  private final TicketRepository tickets;
  private final EventContextBuilder contexts;
  private final ActorContextProvider actors;

  public EventMonitoringService(
      EventRepository events,
      WorkflowVersionRepository versions,
      NodeExecutionRepository nodes,
      TaskExecutionRepository tasks,
      ParticipantSnapshotRepository participants,
      TaskAssignmentHistoryRepository assignments,
      RoutingDecisionRepository routes,
      TicketRepository tickets,
      EventContextBuilder contexts,
      ActorContextProvider actors) {
    this.events = events;
    this.versions = versions;
    this.nodes = nodes;
    this.tasks = tasks;
    this.participants = participants;
    this.assignments = assignments;
    this.routes = routes;
    this.tickets = tickets;
    this.contexts = contexts;
    this.actors = actors;
  }

  @Transactional(readOnly = true)
  public EventMonitoringView get(UUID eventId) {
    Event event =
        events
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    authorize(event);
    WorkflowVersion version = versions.findById(event.getWorkflowVersionId()).orElseThrow();
    List<NodeOccurrence> occurrences = new ArrayList<>();
    List<TaskView> taskViews = new ArrayList<>();
    List<AssignmentView> assignmentViews = new ArrayList<>();
    for (NodeExecution node : nodes.findAllByEventIdOrderByCreatedAtAsc(eventId)) {
      occurrences.add(NodeOccurrence.from(node));
      for (TaskExecution task : tasks.findAllByNodeExecutionIdOrderByCreatedAtAsc(node.getId())) {
        taskViews.add(TaskView.from(task));
        for (TaskAssignmentHistory h : assignments.findAllByTaskIdOrderByCreatedAtAsc(task.getId()))
          assignmentViews.add(AssignmentView.from(h));
      }
    }
    List<ParticipantView> participantViews =
        participants.findAllByEventIdOrderByResolvedAtAsc(eventId).stream()
            .map(ParticipantView::from)
            .toList();
    List<RouteView> routeViews =
        routes.findAllByEventIdOrderByDecidedAtAsc(eventId).stream().map(RouteView::from).toList();
    List<TimelineEntry> timeline = new ArrayList<>();
    occurrences.forEach(
        n -> timeline.add(new TimelineEntry(n.createdAt(), "NODE", n.id(), n.status())));
    taskViews.forEach(
        t -> timeline.add(new TimelineEntry(t.createdAt(), "TASK", t.id(), t.status())));
    participantViews.forEach(
        p -> timeline.add(new TimelineEntry(p.resolvedAt(), "PARTICIPANT", p.id(), "RESOLVED")));
    routeViews.forEach(
        r -> timeline.add(new TimelineEntry(r.decidedAt(), "ROUTING", r.id(), r.routingMode())));
    timeline.sort(Comparator.comparing(TimelineEntry::at).thenComparing(TimelineEntry::type));
    return new EventMonitoringView(
        event.getId(),
        event.getTicketId(),
        new VersionView(
            version.getId(),
            version.getDefinitionId(),
            version.getVersionNo(),
            version.getStatus().name(),
            version.getChecksum()),
        event.getStatus().name(),
        event.getOutcome(),
        List.copyOf(occurrences),
        List.copyOf(taskViews),
        participantViews,
        List.copyOf(assignmentViews),
        routeViews,
        List.copyOf(timeline),
        contexts.build(eventId).maskedValue());
  }

  private void authorize(Event event) {
    ActorContext actor = actors.requireActor();
    Ticket ticket = tickets.findById(event.getTicketId()).orElseThrow();
    if (!ticket.getCreatorId().equals(actor.actorId())
        && !actor.hasRole(RoleKey.OPERATOR)
        && !actor.hasRole(RoleKey.ADMIN))
      throw new AccessDeniedException("Event monitoring is not visible to this actor");
  }

  public record EventMonitoringView(
      UUID eventId,
      UUID ticketId,
      VersionView workflowVersion,
      String status,
      String outcome,
      List<NodeOccurrence> nodeExecutions,
      List<TaskView> tasks,
      List<ParticipantView> participantSnapshots,
      List<AssignmentView> assignmentHistory,
      List<RouteView> routingDecisions,
      List<TimelineEntry> timeline,
      JsonNode maskedContext) {}

  public record VersionView(
      UUID id, UUID definitionId, int versionNo, String status, String checksum) {}

  public record NodeOccurrence(
      UUID id,
      UUID nodeDefinitionId,
      String status,
      String outcomePort,
      UUID cycleId,
      int iteration,
      String path,
      String item,
      UUID splitScopeId,
      UUID joinScopeId,
      Instant createdAt,
      Instant endedAt) {
    static NodeOccurrence from(NodeExecution n) {
      return new NodeOccurrence(
          n.getId(),
          n.getNodeDefinitionId(),
          n.getStatus().name(),
          n.getOutcomePort(),
          n.getCycleId(),
          n.getIteration(),
          n.getPathToken(),
          n.getItemToken(),
          n.getSplitScopeId(),
          n.getJoinScopeId(),
          n.getCreatedAt(),
          n.getEndedAt());
    }
  }

  public record TaskView(
      UUID id,
      UUID nodeExecutionId,
      UUID itemExecutionId,
      String status,
      String outcome,
      UUID assigneeId,
      Instant dueAt,
      Instant createdAt,
      Instant completedAt) {
    static TaskView from(TaskExecution t) {
      return new TaskView(
          t.getId(),
          t.getNodeExecutionId(),
          t.getItemExecutionId(),
          t.getStatus().name(),
          t.getOutcome(),
          t.getAssigneeId(),
          t.getDueAt(),
          t.getCreatedAt(),
          t.getCompletedAt());
    }
  }

  public record ParticipantView(
      UUID id,
      UUID nodeExecutionId,
      UUID itemExecutionId,
      String resolverType,
      String subjectType,
      UUID subjectRefId,
      UUID resolvedUserId,
      Instant resolvedAt) {
    static ParticipantView from(ParticipantSnapshot p) {
      return new ParticipantView(
          p.getId(),
          p.getNodeExecutionId(),
          p.getItemExecutionId(),
          p.getResolverType(),
          p.getSubjectType(),
          p.getSubjectRefId(),
          p.getResolvedUserId(),
          p.getResolvedAt());
    }
  }

  public record AssignmentView(
      UUID id,
      UUID taskId,
      String action,
      UUID fromUserId,
      UUID toUserId,
      UUID actorId,
      String reason,
      Instant at) {
    static AssignmentView from(TaskAssignmentHistory h) {
      return new AssignmentView(
          h.getId(),
          h.getTaskId(),
          h.getActionType().name(),
          h.getFromUserId(),
          h.getToUserId(),
          h.getActorId(),
          h.getReason(),
          h.getCreatedAt());
    }
  }

  public record RouteView(
      UUID id,
      UUID sourceNodeExecutionId,
      String outcomePort,
      String routingMode,
      JsonNode selectedEdgeIds,
      Instant decidedAt) {
    static RouteView from(RoutingDecision r) {
      return new RouteView(
          r.getId(),
          r.getSourceNodeExecutionId(),
          r.getOutcomePort(),
          r.getRoutingMode(),
          r.getSelectedEdgeIdsJson(),
          r.getDecidedAt());
    }
  }

  public record TimelineEntry(Instant at, String type, UUID id, String state) {}
}
