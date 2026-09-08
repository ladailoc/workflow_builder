package com.fpt.workflow.runtime.execution;

import com.fpt.workflow.runtime.activation.NodeActivationService;
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

  public WorkflowExecutionService(
      NodeActivationService activationService,
      RoutingService routingService,
      EventLifecycleService eventLifecycleService) {
    this.activationService = Objects.requireNonNull(activationService, "activationService");
    this.routingService = Objects.requireNonNull(routingService, "routingService");
    this.eventLifecycleService =
        Objects.requireNonNull(eventLifecycleService, "eventLifecycleService");
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

    RoutingResult routingResult = null;
    if (startExecution.getStatus() == NodeExecutionStatus.COMPLETED) {
      routingResult = routingService.route(startExecution.getId(), correlationId, commandId);
      eventLifecycleService.syncEventStatus(eventId);
    }

    return new StartEventResult(startExecution, routingResult);
  }

  public record StartEventResult(NodeExecution rootExecution, RoutingResult routingResult) {}
}
