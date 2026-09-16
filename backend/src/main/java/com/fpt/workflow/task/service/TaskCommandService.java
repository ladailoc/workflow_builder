package com.fpt.workflow.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.lifecycle.EventLifecycleService;
import com.fpt.workflow.runtime.multiinstance.repository.NodeItemExecutionRepository;
import com.fpt.workflow.runtime.multiinstance.service.MultiInstanceService;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.aggregation.RemainingTaskBehavior;
import com.fpt.workflow.task.aggregation.TaskAggregationService;
import com.fpt.workflow.task.domain.TaskCandidate;
import com.fpt.workflow.task.domain.TaskDecision;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskCandidateRepository;
import com.fpt.workflow.task.repository.TaskDecisionRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final MultiInstanceService multiInstanceService;
  private final NodeItemExecutionRepository itemExecutionRepository;
  private final TaskSlaActivationPort slaActivationService;
  private final TaskAggregationService taskAggregationService;
  private final TaskCandidateRepository candidateRepository;
  private final TaskFormValidationService taskFormValidationService;

  @Autowired
  public TaskCommandService(
      TaskExecutionRepository taskRepository,
      TaskDecisionRepository decisionRepository,
      NodeExecutionRepository executionRepository,
      RoutingService routingService,
      EventLifecycleService eventLifecycleService,
      AuditEventRepository auditRepository,
      ActorContextProvider actorProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      MultiInstanceService multiInstanceService,
      NodeItemExecutionRepository itemExecutionRepository,
      TaskSlaActivationPort slaActivationService,
      TaskAggregationService taskAggregationService,
      @Autowired(required = false) TaskCandidateRepository candidateRepository,
      @Autowired(required = false) TaskFormValidationService taskFormValidationService) {
    this.taskRepository = taskRepository;
    this.decisionRepository = decisionRepository;
    this.executionRepository = executionRepository;
    this.routingService = routingService;
    this.eventLifecycleService = eventLifecycleService;
    this.auditRepository = auditRepository;
    this.actorProvider = actorProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.multiInstanceService = multiInstanceService;
    this.itemExecutionRepository = itemExecutionRepository;
    this.slaActivationService = slaActivationService;
    this.taskAggregationService = taskAggregationService;
    this.candidateRepository = candidateRepository;
    this.taskFormValidationService = taskFormValidationService;
  }

  public TaskCommandService(
      TaskExecutionRepository taskRepository,
      TaskDecisionRepository decisionRepository,
      NodeExecutionRepository executionRepository,
      RoutingService routingService,
      EventLifecycleService eventLifecycleService,
      AuditEventRepository auditRepository,
      ActorContextProvider actorProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      MultiInstanceService multiInstanceService,
      NodeItemExecutionRepository itemExecutionRepository,
      TaskSlaActivationPort slaActivationService,
      TaskAggregationService taskAggregationService) {
    this(
        taskRepository,
        decisionRepository,
        executionRepository,
        routingService,
        eventLifecycleService,
        auditRepository,
        actorProvider,
        uuidGenerator,
        clock,
        multiInstanceService,
        itemExecutionRepository,
        slaActivationService,
        taskAggregationService,
        null,
        null);
  }

  /** Backwards-compatible constructor retained for focused unit tests and embedders. */
  public TaskCommandService(
      TaskExecutionRepository taskRepository,
      TaskDecisionRepository decisionRepository,
      NodeExecutionRepository executionRepository,
      RoutingService routingService,
      EventLifecycleService eventLifecycleService,
      AuditEventRepository auditRepository,
      ActorContextProvider actorProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      MultiInstanceService multiInstanceService,
      NodeItemExecutionRepository itemExecutionRepository,
      TaskSlaActivationPort slaActivationService,
      TaskAggregationService taskAggregationService,
      TaskCandidateRepository candidateRepository) {
    this(
        taskRepository,
        decisionRepository,
        executionRepository,
        routingService,
        eventLifecycleService,
        auditRepository,
        actorProvider,
        uuidGenerator,
        clock,
        multiInstanceService,
        itemExecutionRepository,
        slaActivationService,
        taskAggregationService,
        candidateRepository,
        null);
  }

  public TaskCommandService(
      TaskExecutionRepository taskRepository,
      TaskDecisionRepository decisionRepository,
      NodeExecutionRepository executionRepository,
      RoutingService routingService,
      EventLifecycleService eventLifecycleService,
      AuditEventRepository auditRepository,
      ActorContextProvider actorProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      MultiInstanceService multiInstanceService,
      NodeItemExecutionRepository itemExecutionRepository,
      TaskSlaActivationPort slaActivationService) {
    this(
        taskRepository,
        decisionRepository,
        executionRepository,
        routingService,
        eventLifecycleService,
        auditRepository,
        actorProvider,
        uuidGenerator,
        clock,
        multiInstanceService,
        itemExecutionRepository,
        slaActivationService,
        null);
  }

  public TaskCommandService(
      TaskExecutionRepository taskRepository,
      TaskDecisionRepository decisionRepository,
      NodeExecutionRepository executionRepository,
      RoutingService routingService,
      EventLifecycleService eventLifecycleService,
      AuditEventRepository auditRepository,
      ActorContextProvider actorProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      MultiInstanceService multiInstanceService,
      NodeItemExecutionRepository itemExecutionRepository) {
    this(
        taskRepository,
        decisionRepository,
        executionRepository,
        routingService,
        eventLifecycleService,
        auditRepository,
        actorProvider,
        uuidGenerator,
        clock,
        multiInstanceService,
        itemExecutionRepository,
        null);
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

    try (var mdcScope =
        com.fpt.workflow.operations.observability.WorkflowMdcScope.builder()
            .taskId(taskId)
            .commandId(commandId.value())
            .correlationId(correlationId.value())
            .open()) {
      return decideTaskInternal(
          taskId,
          outcome,
          formData,
          comment,
          correlationId,
          commandId,
          actorProvider.requireActor(),
          false);
    }
  }

  /**
   * Completes a task through the canonical task/node/routing pipeline on behalf of a trusted
   * durable automation. This entry point deliberately bypasses end-user assignee authorization,
   * but preserves the explicit automation actor in TaskDecision and audit evidence.
   */
  @Transactional
  public TaskDecisionResult decideTaskByAutomation(
      UUID taskId,
      BusinessOutcome outcome,
      JsonNode formData,
      String comment,
      CorrelationId correlationId,
      CommandId commandId,
      UUID automationActorId,
      String automationPrincipal) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(commandId, "commandId");
    ActorContext actor =
        new ActorContext(
            Objects.requireNonNull(automationActorId, "automationActorId"),
            Objects.requireNonNull(automationPrincipal, "automationPrincipal"),
            java.util.Set.of(),
            java.util.Set.of());
    try (var mdcScope =
        com.fpt.workflow.operations.observability.WorkflowMdcScope.builder()
            .taskId(taskId)
            .commandId(commandId.value())
            .correlationId(correlationId.value())
            .open()) {
      return decideTaskInternal(
          taskId, outcome, formData, comment, correlationId, commandId, actor, true);
    }
  }

  private TaskDecisionResult decideTaskInternal(
      UUID taskId,
      BusinessOutcome outcome,
      JsonNode formData,
      String comment,
      CorrelationId correlationId,
      CommandId commandId,
      ActorContext actor,
      boolean automation) {
    TaskExecution task =
        taskRepository
            .findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

    if (task.getStatus() == TaskStatus.COMPLETED
        || task.getStatus() == TaskStatus.CANCELLED
        || task.getStatus() == TaskStatus.EXPIRED) {
      throw new IllegalStateException("Task is already terminal: " + task.getStatus());
    }

    if (!automation
        && task.getAssigneeId() != null
        && !task.getAssigneeId().equals(actor.actorId())) {
      throw new AccessDeniedException(
          "Actor " + actor.actorId() + " is not authorized to decide task " + taskId);
    }
    if (taskFormValidationService != null) {
      taskFormValidationService.validate(task, formData, actor);
    }
    Instant now = clock.now();

    if (automation) {
      task.forceComplete(outcome, actor.actorId(), now);
    } else {
      if (task.getStatus() == TaskStatus.READY) {
        task.claim(actor.actorId());
      }
      task.complete(outcome, now);
    }
    taskRepository.saveAndFlush(task);
    if (slaActivationService != null) {
      slaActivationService.complete(task.getId(), now);
    }

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

    if (task.getItemExecutionId() != null) {
      var item = itemExecutionRepository.findById(task.getItemExecutionId()).orElseThrow();
      NodeExecution parent = executionRepository.findById(task.getNodeExecutionId()).orElseThrow();
      ObjectNode itemOutput = JsonNodeFactory.instance.objectNode();
      itemOutput.put("decision", outcome.value());
      itemOutput.set("formData", effectiveFormData);
      RoutingResult itemRouting =
          multiInstanceService
              .completeItem(
                  parent.getId(),
                  item.getItemIndex(),
                  outcome.value(),
                  itemOutput,
                  correlationId,
                  commandId)
              .orElse(null);
      eventLifecycleService.syncEventStatus(parent.getEventId());
      recordAudit(task, decision, parent, outcome, actor.actorId(), correlationId, commandId, now);
      return new TaskDecisionResult(task, decision, itemRouting);
    }

    if (taskAggregationService != null
        && taskAggregationService.hasAggregation(task.getNodeExecutionId())) {
      TaskAggregationService.AggregationResult aggResult =
          taskAggregationService.recordTaskDecision(
              task.getNodeExecutionId(), task.getId(), outcome);

      if (aggResult.firstThresholdCrossing()) {
        NodeExecution execution =
            executionRepository
                .findByIdForUpdate(task.getNodeExecutionId())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "NodeExecution missing: " + task.getNodeExecutionId()));

        String finalOutcome = aggResult.outcome().name();
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("decision", finalOutcome);
        output.set("formData", effectiveFormData);
        if (comment != null) {
          output.put("comment", comment);
        }
        output.put("aggregated", true);

        if (aggResult.remainingTaskBehavior() == RemainingTaskBehavior.CANCEL_REMAINING) {
          List<TaskExecution> siblings =
              taskRepository.findAllByNodeExecutionIdOrderByCreatedAtAsc(execution.getId());
          for (TaskExecution sibling : siblings) {
            if (!sibling.getId().equals(task.getId())
                && (sibling.getStatus() == TaskStatus.READY
                    || sibling.getStatus() == TaskStatus.CLAIMED
                    || sibling.getStatus() == TaskStatus.IN_PROGRESS)) {
              sibling.cancel(BusinessOutcome.of(finalOutcome), now);
              taskRepository.save(sibling);
              if (slaActivationService != null) {
                slaActivationService.cancel(sibling.getId(), now);
              }
            }
          }
          taskRepository.flush();
        }

        execution.complete(finalOutcome, output, now);
        executionRepository.saveAndFlush(execution);

        RoutingResult routingResult =
            routingService.route(execution.getId(), correlationId, commandId);
        eventLifecycleService.syncEventStatus(execution.getEventId());
        recordAudit(
            task, decision, execution, outcome, actor.actorId(), correlationId, commandId, now);
        return new TaskDecisionResult(task, decision, routingResult);
      } else {
        NodeExecution execution =
            executionRepository
                .findById(task.getNodeExecutionId())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "NodeExecution missing: " + task.getNodeExecutionId()));
        if (candidateRepository != null) {
          List<TaskCandidate> seqCandidates =
              candidateRepository.findAllByTaskIdOrderByCreatedAtAsc(task.getId()).stream()
                  .filter(c -> "SEQUENTIAL".equalsIgnoreCase(c.getSourceType()))
                  .sorted(
                      Comparator.comparingInt(
                          c -> c.getSourceSnapshotJson().path("orderIndex").asInt(0)))
                  .toList();
          if (!seqCandidates.isEmpty()) {
            TaskCandidate nextCand = seqCandidates.get(0);
            UUID nextTaskId = uuidGenerator.generate();
            TaskExecution nextTask =
                TaskExecution.create(
                    nextTaskId,
                    task.getNodeExecutionId(),
                    task.getItemExecutionId(),
                    nextCand.getUserId(),
                    task.getTitleSnapshot(),
                    task.getDescriptionSnapshot(),
                    task.getFormSchemaJson(),
                    task.getInputSnapshotJson(),
                    task.getPriority(),
                    task.getDueAt(),
                    now);
            taskRepository.saveAndFlush(nextTask);

            for (int i = 1; i < seqCandidates.size(); i++) {
              TaskCandidate rem = seqCandidates.get(i);
              candidateRepository.save(
                  TaskCandidate.create(
                      nextTaskId,
                      rem.getUserId(),
                      "SEQUENTIAL",
                      rem.getSourceSnapshotJson(),
                      now));
            }
            candidateRepository.flush();
          }
        }
        recordAudit(
            task, decision, execution, outcome, actor.actorId(), correlationId, commandId, now);
        return new TaskDecisionResult(task, decision, null);
      }
    }

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

    recordAudit(
        task, decision, execution, outcome, actor.actorId(), correlationId, commandId, now, "TASK_DECIDED");

    return new TaskDecisionResult(task, decision, routingResult);
  }

  @Transactional
  public TaskDecisionResult forceCompleteTask(
      UUID taskId,
      BusinessOutcome outcome,
      String reason,
      JsonNode formData,
      CorrelationId correlationId,
      CommandId commandId) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(commandId, "commandId");

    ActorContext actor = actorProvider.requireActor();
    if (!actor.hasRole(RoleKey.ADMIN) && !actor.hasRole(RoleKey.OPERATOR)) {
      throw new AccessDeniedException("Actor is not authorized to force complete task: " + taskId);
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Operator reason is required for forceComplete");
    }

    TaskExecution task =
        taskRepository
            .findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

    if (task.getStatus() == TaskStatus.COMPLETED
        || task.getStatus() == TaskStatus.CANCELLED
        || task.getStatus() == TaskStatus.EXPIRED) {
      throw new IllegalStateException("Task is already terminal: " + task.getStatus());
    }

    Instant now = clock.now();
    task.forceComplete(outcome, actor.actorId(), now);
    taskRepository.saveAndFlush(task);

    if (slaActivationService != null) {
      slaActivationService.complete(task.getId(), now);
    }

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
            reason,
            now);
    decisionRepository.saveAndFlush(decision);

    if (task.getItemExecutionId() != null) {
      var item = itemExecutionRepository.findById(task.getItemExecutionId()).orElseThrow();
      NodeExecution parent = executionRepository.findById(task.getNodeExecutionId()).orElseThrow();
      ObjectNode itemOutput = JsonNodeFactory.instance.objectNode();
      itemOutput.put("decision", outcome.value());
      itemOutput.set("formData", effectiveFormData);
      RoutingResult itemRouting =
          multiInstanceService
              .completeItem(
                  parent.getId(),
                  item.getItemIndex(),
                  outcome.value(),
                  itemOutput,
                  correlationId,
                  commandId)
              .orElse(null);
      eventLifecycleService.syncEventStatus(parent.getEventId());
      recordAudit(
          task, decision, parent, outcome, actor.actorId(), correlationId, commandId, now, "TASK_FORCE_COMPLETED");
      return new TaskDecisionResult(task, decision, itemRouting);
    }

    if (taskAggregationService != null
        && taskAggregationService.hasAggregation(task.getNodeExecutionId())) {
      TaskAggregationService.AggregationResult aggResult =
          taskAggregationService.recordTaskDecision(
              task.getNodeExecutionId(), task.getId(), outcome);

      if (aggResult.firstThresholdCrossing()) {
        NodeExecution execution =
            executionRepository
                .findByIdForUpdate(task.getNodeExecutionId())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "NodeExecution missing: " + task.getNodeExecutionId()));

        String finalOutcome = aggResult.outcome().name();
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("decision", finalOutcome);
        output.set("formData", effectiveFormData);
        output.put("comment", reason);
        output.put("aggregated", true);

        if (aggResult.remainingTaskBehavior() == RemainingTaskBehavior.CANCEL_REMAINING) {
          List<TaskExecution> siblings =
              taskRepository.findAllByNodeExecutionIdOrderByCreatedAtAsc(execution.getId());
          for (TaskExecution sibling : siblings) {
            if (!sibling.getId().equals(task.getId())
                && (sibling.getStatus() == TaskStatus.READY
                    || sibling.getStatus() == TaskStatus.CLAIMED
                    || sibling.getStatus() == TaskStatus.IN_PROGRESS)) {
              sibling.cancel(BusinessOutcome.of(finalOutcome), now);
              taskRepository.save(sibling);
              if (slaActivationService != null) {
                slaActivationService.cancel(sibling.getId(), now);
              }
            }
          }
          taskRepository.flush();
        }

        execution.complete(finalOutcome, output, now);
        executionRepository.saveAndFlush(execution);

        RoutingResult routingResult =
            routingService.route(execution.getId(), correlationId, commandId);
        eventLifecycleService.syncEventStatus(execution.getEventId());
        recordAudit(
            task, decision, execution, outcome, actor.actorId(), correlationId, commandId, now, "TASK_FORCE_COMPLETED");
        return new TaskDecisionResult(task, decision, routingResult);
      } else {
        NodeExecution execution =
            executionRepository
                .findById(task.getNodeExecutionId())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "NodeExecution missing: " + task.getNodeExecutionId()));
        recordAudit(
            task, decision, execution, outcome, actor.actorId(), correlationId, commandId, now, "TASK_FORCE_COMPLETED");
        return new TaskDecisionResult(task, decision, null);
      }
    }

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
    output.put("comment", reason);
    output.put("forceCompleted", true);
    execution.complete(outcome.value(), output, now);
    executionRepository.saveAndFlush(execution);

    RoutingResult routingResult = routingService.route(execution.getId(), correlationId, commandId);
    eventLifecycleService.syncEventStatus(execution.getEventId());
    recordAudit(
        task, decision, execution, outcome, actor.actorId(), correlationId, commandId, now, "TASK_FORCE_COMPLETED");

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
    recordAudit(
        task,
        decision,
        execution,
        outcome,
        actorId,
        correlationId,
        commandId,
        occurredAt,
        "TASK_DECIDED");
  }

  private void recordAudit(
      TaskExecution task,
      TaskDecision decision,
      NodeExecution execution,
      BusinessOutcome outcome,
      UUID actorId,
      CorrelationId correlationId,
      CommandId commandId,
      Instant occurredAt,
      String eventType) {
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
            eventType,
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
