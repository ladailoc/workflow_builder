package com.fpt.workflow.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
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
  private final NodeDefinitionRepository nodeDefinitions;
  private final EdgeDefinitionRepository edgeDefinitions;
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
      NodeDefinitionRepository nodeDefinitions,
      EdgeDefinitionRepository edgeDefinitions,
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
    this.nodeDefinitions = nodeDefinitions;
    this.edgeDefinitions = edgeDefinitions;
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
    GraphView graph =
        new GraphView(
            nodeDefinitions.findAllByWorkflowVersionIdOrderByNodeKeyAsc(version.getId()).stream()
                .map(GraphNode::from)
                .toList(),
            edgeDefinitions
                .findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(version.getId())
                .stream()
                .map(GraphEdge::from)
                .toList());
    List<NodeExecution> eventNodes = nodes.findAllByEventIdOrderByCreatedAtAsc(eventId);
    List<NodeOccurrence> occurrences = eventNodes.stream().map(NodeOccurrence::from).toList();
    List<TaskView> taskViews = new ArrayList<>();
    List<AssignmentView> assignmentViews = new ArrayList<>();
    if (!eventNodes.isEmpty()) {
      List<UUID> nodeIds = eventNodes.stream().map(NodeExecution::getId).toList();
      List<TaskExecution> eventTasks = tasks.findAllByNodeExecutionIdInOrderByCreatedAtAsc(nodeIds);
      taskViews = eventTasks.stream().map(TaskView::from).toList();
      if (!eventTasks.isEmpty()) {
        List<UUID> taskIds = eventTasks.stream().map(TaskExecution::getId).toList();
        assignmentViews =
            assignments.findAllByTaskIdInOrderByCreatedAtAsc(taskIds).stream()
                .map(AssignmentView::from)
                .toList();
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
        graph,
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

  @Transactional(readOnly = true)
  public List<EventSummaryView> listEvents() {
    ActorContext actor = actors.requireActor();
    List<Event> eventList;
    if (actor.hasRole(RoleKey.OPERATOR) || actor.hasRole(RoleKey.ADMIN)) {
      eventList =
          events.findAll(
              org.springframework.data.domain.Sort.by(
                  org.springframework.data.domain.Sort.Direction.DESC, "startedAt"));
    } else {
      List<Ticket> myTickets = tickets.findAllByCreatorIdOrderByCreatedAtDesc(actor.actorId());
      Set<UUID> myTicketIds =
          myTickets.stream().map(Ticket::getId).collect(java.util.stream.Collectors.toSet());
      eventList =
          events
              .findAll(
                  org.springframework.data.domain.Sort.by(
                      org.springframework.data.domain.Sort.Direction.DESC, "startedAt"))
              .stream()
              .filter(e -> myTicketIds.contains(e.getTicketId()))
              .toList();
    }
    return eventList.stream()
        .map(
            e ->
                new EventSummaryView(
                    e.getId(),
                    e.getTicketId(),
                    e.getStatus().name(),
                    e.getOutcome(),
                    e.getStartedAt()))
        .toList();
  }

  public record EventSummaryView(
      UUID id, UUID ticketId, String status, String outcome, Instant createdAt) {}

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
      GraphView graph,
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

  public record GraphView(List<GraphNode> nodes, List<GraphEdge> edges) {}

  public record GraphNode(UUID id, String key, String type, String name, JsonNode position) {
    static GraphNode from(NodeDefinition node) {
      return new GraphNode(
          node.getId(),
          node.getNodeKey(),
          node.getNodeType(),
          node.getName(),
          node.getPositionJson());
    }
  }

  public record GraphEdge(
      UUID id,
      UUID sourceNodeId,
      String sourcePort,
      UUID targetNodeId,
      String label,
      String transitionType) {
    static GraphEdge from(EdgeDefinition edge) {
      return new GraphEdge(
          edge.getId(),
          edge.getSourceNodeId(),
          edge.getSourcePort(),
          edge.getTargetNodeId(),
          edge.getLabel(),
          edge.getTransitionType().name());
    }
  }

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
