package com.fpt.workflow.runtime.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.nodetype.NodeCapability;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
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
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the Event aggregate lifecycle, branch synchronization, cancel, terminate, and restart.
 */
@Service
public class EventLifecycleService {

  public static final Set<EventStatus> TERMINAL_EVENT_STATUSES =
      EnumSet.of(
          EventStatus.COMPLETED, EventStatus.FAILED, EventStatus.CANCELLED, EventStatus.TERMINATED);

  public static final Set<NodeExecutionStatus> RUNNABLE_NODE_STATUSES =
      EnumSet.of(
          NodeExecutionStatus.CREATED, NodeExecutionStatus.READY, NodeExecutionStatus.RUNNING);

  public static final Set<NodeExecutionStatus> TERMINAL_NODE_STATUSES =
      EnumSet.of(
          NodeExecutionStatus.COMPLETED,
          NodeExecutionStatus.FAILED,
          NodeExecutionStatus.CANCELLED,
          NodeExecutionStatus.SKIPPED);

  private final EventRepository eventRepository;
  private final NodeExecutionRepository nodeExecutionRepository;
  private final NodeDefinitionRepository nodeRepository;
  private final NodeTypeRegistry registry;
  private final ActiveTaskCancellationPort taskCancellationPort;
  private final AuditEventRepository auditRepository;
  private final ActorContextProvider actorProvider;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;

  public EventLifecycleService(
      EventRepository eventRepository,
      NodeExecutionRepository nodeExecutionRepository,
      NodeDefinitionRepository nodeRepository,
      NodeTypeRegistry registry,
      ActiveTaskCancellationPort taskCancellationPort,
      AuditEventRepository auditRepository,
      ActorContextProvider actorProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper) {
    this.eventRepository = eventRepository;
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.nodeRepository = nodeRepository;
    this.registry = registry;
    this.taskCancellationPort = taskCancellationPort;
    this.auditRepository = auditRepository;
    this.actorProvider = actorProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public EventStatus syncEventStatus(UUID eventId) {
    Event event =
        eventRepository
            .findByIdForUpdate(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    if (TERMINAL_EVENT_STATUSES.contains(event.getStatus())) {
      return event.getStatus();
    }
    List<NodeExecution> executions =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(eventId);
    return syncEventStatus(event, executions);
  }

  @Transactional
  public EventStatus syncEventStatus(Event event, List<NodeExecution> executions) {
    if (TERMINAL_EVENT_STATUSES.contains(event.getStatus())) {
      return event.getStatus();
    }
    if (executions == null || executions.isEmpty()) {
      return event.getStatus();
    }

    boolean hasRunnable =
        executions.stream().anyMatch(e -> RUNNABLE_NODE_STATUSES.contains(e.getStatus()));
    boolean hasWaiting =
        executions.stream().anyMatch(e -> e.getStatus() == NodeExecutionStatus.WAITING);

    if (hasRunnable) {
      if (event.getStatus() != EventStatus.RUNNING) {
        event.markRunning();
      }
    } else if (hasWaiting) {
      RuntimeWaitReason reason =
          executions.stream()
              .filter(
                  e -> e.getStatus() == NodeExecutionStatus.WAITING && e.getWaitReason() != null)
              .map(NodeExecution::getWaitReason)
              .findFirst()
              .orElse(RuntimeWaitReason.HUMAN_TASK);
      if (event.getStatus() != EventStatus.WAITING) {
        event.waitFor(reason);
      }
    } else {
      Instant now = clock.now();
      boolean hasFailed =
          executions.stream().anyMatch(e -> e.getStatus() == NodeExecutionStatus.FAILED);
      if (hasFailed) {
        event.fail(now);
      } else {
        Optional<NodeExecution> endExecution =
            executions.stream()
                .filter(e -> e.getStatus() == NodeExecutionStatus.COMPLETED)
                .filter(this::isTerminalNode)
                .findFirst();
        String outcome =
            endExecution
                .map(
                    e -> {
                      if (e.getOutputJson() != null && e.getOutputJson().hasNonNull("outcome")) {
                        return e.getOutputJson().get("outcome").asText();
                      }
                      return e.getOutcomePort();
                    })
                .orElse("COMPLETED");
        event.complete(outcome, now);
      }
    }
    eventRepository.save(event);
    return event.getStatus();
  }

  @Transactional
  public Event cancelEvent(
      UUID eventId, CommandId commandId, CorrelationId correlationId, String reason) {
    Event event =
        eventRepository
            .findByIdForUpdate(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    if (TERMINAL_EVENT_STATUSES.contains(event.getStatus())) {
      throw new IllegalStateException("Cannot cancel terminal Event: " + event.getStatus());
    }
    ActorContext actor = actorProvider.requireActor();
    Instant now = clock.now();

    List<NodeExecution> executions =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(eventId);
    for (NodeExecution execution : executions) {
      if (!TERMINAL_NODE_STATUSES.contains(execution.getStatus())) {
        execution.cancel(now);
        nodeExecutionRepository.save(execution);
        taskCancellationPort.cancelActiveTasks(execution.getId(), now);
      }
    }

    String outcome = "CANCELLED";
    event.cancel(outcome, now);
    eventRepository.saveAndFlush(event);

    recordAudit(event, "EVENT_CANCELLED", actor.actorId(), correlationId, commandId, reason, now);
    return event;
  }

  @Transactional
  public Event terminateEvent(
      UUID eventId, CommandId commandId, CorrelationId correlationId, String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Termination reason is mandatory");
    }
    ActorContext actor = actorProvider.requireActor();
    if (!actor.hasRole(RoleKey.ADMIN) && !actor.hasRole(RoleKey.OPERATOR)) {
      throw new AccessDeniedException("TERMINATE_EVENT requires ADMIN or OPERATOR role");
    }
    Event event =
        eventRepository
            .findByIdForUpdate(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    if (TERMINAL_EVENT_STATUSES.contains(event.getStatus())) {
      throw new IllegalStateException("Cannot terminate terminal Event: " + event.getStatus());
    }
    Instant now = clock.now();

    List<NodeExecution> executions =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(eventId);
    for (NodeExecution execution : executions) {
      if (!TERMINAL_NODE_STATUSES.contains(execution.getStatus())) {
        execution.cancel(now);
        nodeExecutionRepository.save(execution);
        taskCancellationPort.cancelActiveTasks(execution.getId(), now);
      }
    }

    event.terminate("TERMINATED", now);
    eventRepository.saveAndFlush(event);

    recordAudit(event, "EVENT_TERMINATED", actor.actorId(), correlationId, commandId, reason, now);
    return event;
  }

  @Transactional
  public Event restartEvent(
      UUID eventId,
      CommandId commandId,
      CorrelationId correlationId,
      UUID targetWorkflowVersionId) {
    Event previous =
        eventRepository
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    if (!TERMINAL_EVENT_STATUSES.contains(previous.getStatus())) {
      throw new IllegalStateException(
          "Restart requires a terminal Event; current status is " + previous.getStatus());
    }
    ActorContext actor = actorProvider.requireActor();
    Instant now = clock.now();

    UUID newEventId = uuidGenerator.generate();
    UUID versionId =
        targetWorkflowVersionId != null ? targetWorkflowVersionId : previous.getWorkflowVersionId();
    UUID restartedFromId =
        previous.getRestartedFromEventId() != null
            ? previous.getRestartedFromEventId()
            : previous.getId();

    Event newEvent =
        Event.createRoot(
            newEventId,
            previous.getTicketId(),
            versionId,
            previous.getStartedTicketRevisionId(),
            previous.getId(),
            restartedFromId,
            "RESTART",
            commandId != null ? commandId.toString() : null,
            previous.getVariablesJson(),
            actor.actorId(),
            now);

    eventRepository.saveAndFlush(newEvent);
    recordAudit(
        newEvent,
        "EVENT_RESTARTED",
        actor.actorId(),
        correlationId,
        commandId,
        "Restarted from " + previous.getId(),
        now);
    return newEvent;
  }

  private boolean isTerminalNode(NodeExecution execution) {
    Optional<NodeDefinition> nodeOpt = nodeRepository.findById(execution.getNodeDefinitionId());
    if (nodeOpt.isEmpty()) return false;
    NodeDefinition node = nodeOpt.get();
    try {
      NodeType nodeType = NodeType.valueOf(node.getNodeType());
      NodeTypeManifest manifest = registry.require(nodeType);
      return manifest.supportedCapabilities().contains(NodeCapability.TERMINAL);
    } catch (Exception ignored) {
      return false;
    }
  }

  private void recordAudit(
      Event event,
      String action,
      UUID actorId,
      CorrelationId correlationId,
      CommandId commandId,
      String reason,
      Instant occurredAt) {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("eventId", event.getId().toString());
    metadata.put("ticketId", event.getTicketId().toString());
    metadata.put("status", event.getStatus().name());
    if (event.getOutcome() != null) {
      metadata.put("outcome", event.getOutcome());
    }
    if (reason != null && !reason.isBlank()) {
      metadata.put("reason", reason.trim());
    }
    auditRepository.save(
        AuditEvent.record(
            uuidGenerator.generate(),
            "EVENT",
            event.getId(),
            action,
            actorId,
            actorId,
            correlationId,
            commandId,
            metadata,
            occurredAt));
  }
}
