package com.fpt.workflow.runtime.execution;

import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.lifecycle.EventLifecycleService;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates event startup and automated routing from entry nodes. */
@Service
public class WorkflowExecutionService {

  private final NodeActivationService activationService;
  private final RoutingService routingService;
  private final EventLifecycleService eventLifecycleService;
  private final com.fpt.workflow.operations.audit.AuditEventRepository auditRepository;
  private final com.fpt.workflow.shared.UuidGenerator uuidGenerator;
  private final com.fpt.workflow.shared.time.PlatformClock clock;
  private final com.fpt.workflow.runtime.repository.EventRepository eventRepository;

  public WorkflowExecutionService(
      NodeActivationService activationService,
      RoutingService routingService,
      EventLifecycleService eventLifecycleService) {
    this(activationService, routingService, eventLifecycleService, null, null, null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public WorkflowExecutionService(
      NodeActivationService activationService,
      RoutingService routingService,
      EventLifecycleService eventLifecycleService,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.operations.audit.AuditEventRepository auditRepository,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.shared.UuidGenerator uuidGenerator,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.shared.time.PlatformClock clock,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.runtime.repository.EventRepository eventRepository) {
    this.activationService = Objects.requireNonNull(activationService, "activationService");
    this.routingService = Objects.requireNonNull(routingService, "routingService");
    this.eventLifecycleService =
        Objects.requireNonNull(eventLifecycleService, "eventLifecycleService");
    this.auditRepository = auditRepository;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.eventRepository = eventRepository;
  }

  @Transactional
  public StartEventResult startEvent(
      UUID eventId, UUID cycleId, CorrelationId correlationId, CommandId commandId) {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(cycleId, "cycleId");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(commandId, "commandId");

    NodeExecution startExecution =
        activationService.activateRoot(eventId, cycleId, correlationId, commandId);

    // P2-16 (§24.1): EVENT_STARTED is part of the normative audit vocabulary. Emitted exactly
    // once per start; durable-job replays never reach this line twice for the same event because
    // activation is idempotent on the root activation key.
    recordEventStarted(eventId, startExecution, correlationId, commandId);

    RoutingResult routingResult = null;
    if (startExecution.getStatus() == NodeExecutionStatus.COMPLETED) {
      routingResult = routingService.route(startExecution.getId(), correlationId, commandId);
      eventLifecycleService.syncEventStatus(eventId);
    }

    return new StartEventResult(startExecution, routingResult);
  }

  private void recordEventStarted(
      UUID eventId,
      NodeExecution startExecution,
      CorrelationId correlationId,
      CommandId commandId) {
    if (auditRepository == null || uuidGenerator == null) {
      return;
    }
    com.fasterxml.jackson.databind.node.ObjectNode metadata =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    metadata.put("eventId", eventId.toString());
    if (startExecution != null) {
      metadata.put("rootNodeExecutionId", startExecution.getId().toString());
    }
    java.util.UUID actorId = null;
    try {
      actorId =
          eventRepository != null
              ? eventRepository.findById(eventId).map(Event::getStartedBy).orElse(null)
              : null;
    } catch (RuntimeException ignored) {
      // Audit must never block the start path.
    }
    auditRepository.save(
        com.fpt.workflow.operations.audit.AuditEvent.record(
            uuidGenerator.generate(),
            "EVENT",
            eventId,
            "EVENT_STARTED",
            actorId,
            actorId,
            correlationId,
            commandId,
            metadata,
            clock != null ? clock.now() : java.time.Instant.now()));
  }

  public record StartEventResult(NodeExecution rootExecution, RoutingResult routingResult) {}
}
