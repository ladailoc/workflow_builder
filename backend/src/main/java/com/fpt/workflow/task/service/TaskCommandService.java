package com.fpt.workflow.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.lifecycle.EventLifecycleService;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskDecision;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskDecisionRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes human task decision commands, completes task/node executions, triggers edge routing, and
 * audits the transition.
 */
@Service
public class TaskCommandService {

  private final TaskExecutionRepository taskRepository;
  private final TaskDecisionRepository decisionRepository;
  private final NodeExecutionRepository executionRepository;
  private final RoutingService routingService;
  private final EventLifecycleService eventLifecycleService;
  private final AuditEventRepository auditRepository;
  private final ActorContextProvider actorProvider;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;

  public TaskCommandService(
      TaskExecutionRepository taskRepository,
      TaskDecisionRepository decisionRepository,
      NodeExecutionRepository executionRepository,
      RoutingService routingService,
      EventLifecycleService eventLifecycleService,
      AuditEventRepository auditRepository,
      ActorContextProvider actorProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock) {
    this.taskRepository = taskRepository;
    this.decisionRepository = decisionRepository;
    this.executionRepository = executionRepository;
    this.routingService = routingService;
    this.eventLifecycleService = eventLifecycleService;
    this.auditRepository = auditRepository;
    this.actorProvider = actorProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
  }

  @Transactional
  public TaskDecisionResult decideTask(
      UUID taskId,
      BusinessOutcome outcome,
      JsonNode formData,
      String comment,
      CorrelationId correlationId,
      CommandId commandId) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(commandId, "commandId");

    TaskExecution task =
        taskRepository
            .findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

    if (task.getStatus() == TaskStatus.COMPLETED
        || task.getStatus() == TaskStatus.CANCELLED
        || task.getStatus() == TaskStatus.EXPIRED) {
      throw new IllegalStateException("Task is already terminal: " + task.getStatus());
    }

    ActorContext actor = actorProvider.requireActor();
    if (task.getAssigneeId() != null && !task.getAssigneeId().equals(actor.actorId())) {
      throw new AccessDeniedException(
          "Actor " + actor.actorId() + " is not authorized to decide task " + taskId);
    }
    Instant now = clock.now();

    if (task.getStatus() == TaskStatus.READY) {
      task.claim(actor.actorId());
    }
    task.complete(outcome, now);
    taskRepository.saveAndFlush(task);

    JsonNode effectiveFormData =
        formData != null ? formData : JsonNodeFactory.instance.objectNode();
    TaskDecision decision =
        TaskDecision.create(
            uuidGenerator.generate(),
            task.getId(),
            commandId.value(),
            actor.actorId(),
            null,
            outcome,
            effectiveFormData,
            comment,
            now);
    decisionRepository.saveAndFlush(decision);

    // Complete corresponding NodeExecution
    NodeExecution execution =
        executionRepository
            .findByIdForUpdate(task.getNodeExecutionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "NodeExecution missing: " + task.getNodeExecutionId()));

    ObjectNode output = JsonNodeFactory.instance.objectNode();
    output.put("decision", outcome.value());
    output.set("formData", effectiveFormData);
    if (comment != null) {
      output.put("comment", comment);
    }
    execution.complete(outcome.value(), output, now);
    executionRepository.saveAndFlush(execution);

    // Route downstream
    RoutingResult routingResult = routingService.route(execution.getId(), correlationId, commandId);

    // Synchronize event aggregate status
    eventLifecycleService.syncEventStatus(execution.getEventId());

    recordAudit(task, decision, execution, outcome, actor.actorId(), correlationId, commandId, now);

    return new TaskDecisionResult(task, decision, routingResult);
  }

  private void recordAudit(
      TaskExecution task,
      TaskDecision decision,
      NodeExecution execution,
      BusinessOutcome outcome,
      UUID actorId,
      CorrelationId correlationId,
      CommandId commandId,
      Instant occurredAt) {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("taskId", task.getId().toString());
    metadata.put("nodeExecutionId", execution.getId().toString());
    metadata.put("eventId", execution.getEventId().toString());
    metadata.put("outcome", outcome.value());
    auditRepository.save(
        AuditEvent.record(
            uuidGenerator.generate(),
            "TASK_EXECUTION",
            task.getId(),
            "TASK_DECIDED",
            actorId,
            actorId,
            correlationId,
            commandId,
            metadata,
            occurredAt));
  }

  public record TaskDecisionResult(
      TaskExecution task, TaskDecision decision, RoutingResult routingResult) {}
}
