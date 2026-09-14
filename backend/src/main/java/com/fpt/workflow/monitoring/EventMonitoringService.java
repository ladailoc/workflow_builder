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
import com.fpt.workflow.security.visibility.VisibilityResolver;
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
  private final EventContextBuilder contexts;
  private final ActorContextProvider actors;
  private final VisibilityResolver visibilityResolver;

  private final com.fpt.workflow.sla.repository.SlaExecutionRepository slaExecutions;
  private final com.fpt.workflow.integration.repository.IntegrationExecutionRepository integrationExecutions;
  private final com.fpt.workflow.operations.audit.AuditEventRepository auditEvents;

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
      EventContextBuilder contexts,
      ActorContextProvider actors,
      VisibilityResolver visibilityResolver) {
    this(
        events,
        versions,
        nodeDefinitions,
        edgeDefinitions,
        nodes,
        tasks,
        participants,
        assignments,
        routes,
        contexts,
        actors,
        visibilityResolver,
        null,
        null,
        null);
  }

  @org.springframework.beans.factory.annotation.Autowired
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
      EventContextBuilder contexts,
      ActorContextProvider actors,
      VisibilityResolver visibilityResolver,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.sla.repository.SlaExecutionRepository slaExecutions,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.integration.repository.IntegrationExecutionRepository
          integrationExecutions,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.operations.audit.AuditEventRepository auditEvents) {
    this.events = events;
    this.versions = versions;
    this.nodeDefinitions = nodeDefinitions;
    this.edgeDefinitions = edgeDefinitions;
    this.nodes = nodes;
    this.tasks = tasks;
    this.participants = participants;
    this.assignments = assignments;
    this.routes = routes;
    this.contexts = contexts;
    this.actors = actors;
    this.visibilityResolver = visibilityResolver;
    this.slaExecutions = slaExecutions;
    this.integrationExecutions = integrationExecutions;
    this.auditEvents = auditEvents;
  }

  @Transactional(readOnly = true)
  public EventMonitoringView get(UUID eventId) {
    Event event =
        events
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    visibilityResolver.requireEventVisible(actors.requireActor(), event.getId());
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
    // P2-18: enriched timeline sources — integration, SLA, child events, audit rework/events
    if (integrationExecutions != null) {
      try {
        integrationExecutions.findAllByEventIdOrderByCreatedAtAsc(eventId).stream()
            .forEach(
                e -> timeline.add(new TimelineEntry(e.getCreatedAt(), "INTEGRATION", e.getId(), e.getStatus().name())));
      } catch (RuntimeException ignored) {
      }
    }
    if (slaExecutions != null) {
      try {
        slaExecutions.findAllByEventIdOrderByStartedAtAsc(eventId).stream()
            .forEach(
                e -> timeline.add(new TimelineEntry(e.getStartedAt(), "SLA", e.getId(), e.getStatus())));
      } catch (RuntimeException ignored) {
      }
    }
    if (auditEvents != null) {
      try {
        for (com.fpt.workflow.operations.audit.AuditEvent a :
            auditEvents.findAllByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(
                "EVENT", eventId)) {
          timeline.add(new TimelineEntry(a.getOccurredAt(), "AUDIT:" + a.getAggregateType(), a.getId(), a.getEventType()));
        }
        // Child events: list children of this event via their parent link.
        for (Event child : events.findAllByParentEventIdOrderByStartedAtAsc(eventId)) {
          timeline.add(new TimelineEntry(child.getStartedAt(), "CHILD_EVENT", child.getId(), child.getStatus().name()));
        }
      } catch (RuntimeException ignored) {
      }
    }
    timeline.sort(Comparator.comparing(TimelineEntry::at).thenComparing(TimelineEntry::type));
    // P2-18: bounded payload — timeline is the only unbounded list in the view; cap it explicitly.
    List<TimelineEntry> boundedTimeline =
        timeline.size() > 1000 ? List.copyOf(timeline.subList(0, 1000)) : List.copyOf(timeline);
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
        boundedTimeline,
        contexts.build(eventId).maskedValue());
  }

  /** P2-19: paged timeline slice of the (already visibility-guarded) event view. */
  @Transactional(readOnly = true)
  public List<TimelineEntry> timeline(UUID eventId, int page, int size) {
    EventMonitoringView view = get(eventId);
    List<TimelineEntry> all = view.timeline();
    int from = Math.max(0, Math.min(page * Math.max(1, size), all.size()));
    int to = Math.min(from + Math.max(1, size), all.size());
    return List.copyOf(all.subList(from, to));
  }

  /** P2-19: version-pinned graph for the event's bound WorkflowVersion. */
  @Transactional(readOnly = true)
  public GraphView graph(UUID eventId) {
    EventMonitoringView view = get(eventId);
    return view.graph();
  }

  /** P2-19: masked (safe) event context only — raw context is never exposed through the API. */
  @Transactional(readOnly = true)
  public com.fasterxml.jackson.databind.JsonNode safeContext(UUID eventId) {
    EventMonitoringView view = get(eventId);
    return view.maskedContext();
  }

  @Transactional(readOnly = true)
  public List<EventSummaryView> listEvents() {
    ActorContext actor = actors.requireActor();
    List<Event> eventList =
        events
            .findAll(
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "startedAt"))
            .stream()
            .filter(event -> visibilityResolver.mayViewEvent(actor, event.getId()))
            .toList();
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

  /** P2-11: evaluated edge evidence for routing debug. */
  public record EvaluatedEdgeView(
      String edgeId,
      int priority,
      boolean defaultTransition,
      boolean conditionPresent,
      boolean selected,
      Boolean matched,
      String evaluationError) {}

  public record RouteView(
      UUID id,
      UUID sourceNodeExecutionId,
      String outcomePort,
      String routingMode,
      JsonNode selectedEdgeIds,
      JsonNode evaluatedEdges,
      Instant decidedAt) {
    static RouteView from(RoutingDecision r) {
      return new RouteView(
          r.getId(),
          r.getSourceNodeExecutionId(),
          r.getOutcomePort(),
          r.getRoutingMode(),
          r.getSelectedEdgeIdsJson(),
          r.getEvaluatedEdgesJson(),
          r.getDecidedAt());
    }

    /** Monitoring-oriented evidence list for observable debugging (§12.5). */
    public List<EvaluatedEdgeView> evaluatedEdgesView() {
      JsonNode json = evaluatedEdges;
      List<EvaluatedEdgeView> result = new ArrayList<>();
      if (json == null || !json.isArray()) return List.copyOf(result);
      for (JsonNode row : json) {
        JsonNode error = row.path("evaluationError");
        String errorType =
            error.isObject() ? error.path("type").asText(null) : (error.isTextual() ? error.asText() : null);
        result.add(
            new EvaluatedEdgeView(
                row.path("edgeId").asText(null),
                row.path("priority").asInt(0),
                row.path("defaultTransition").asBoolean(false),
                row.path("conditionPresent").asBoolean(false),
                row.path("selected").asBoolean(false),
                row.path("matched").isBoolean() ? row.path("matched").asBoolean() : null,
                errorType));
      }
      return List.copyOf(result);
    }
  }

  public record TimelineEntry(Instant at, String type, UUID id, String state) {}
}
