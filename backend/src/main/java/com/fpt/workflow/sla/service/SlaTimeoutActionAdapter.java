package com.fpt.workflow.sla.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.lifecycle.EventLifecycleService;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.sla.domain.SlaExecution;
import com.fpt.workflow.task.domain.TaskCandidate;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskCandidateRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.task.service.TaskCommandService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Executes SLA timeout actions through the normal task, node, routing, and lifecycle machinery. */
@Component
public class SlaTimeoutActionAdapter implements SlaTimeoutActionPort {

  private static final UUID SLA_AUTOMATION_ACTOR =
      UUID.nameUUIDFromBytes("WORKFLOW_PLATFORM:SLA".getBytes(StandardCharsets.UTF_8));
  private static final Set<TaskStatus> TERMINAL_TASKS =
      EnumSet.of(TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.EXPIRED);

  private final TaskCommandService taskCommands;
  private final TaskExecutionRepository tasks;
  private final TaskCandidateRepository candidates;
  private final NodeExecutionRepository executions;
  private final EventRepository events;
  private final NodeDefinitionRepository nodes;
  private final RoutingService routing;
  private final EventLifecycleService lifecycle;
  private final SlaParticipantResolutionService participants;
  private final AuditEventRepository audits;
  private final UuidGenerator uuids;

  public SlaTimeoutActionAdapter(
      TaskCommandService taskCommands,
      TaskExecutionRepository tasks,
      TaskCandidateRepository candidates,
      NodeExecutionRepository executions,
      EventRepository events,
      NodeDefinitionRepository nodes,
      RoutingService routing,
      EventLifecycleService lifecycle,
      SlaParticipantResolutionService participants,
      AuditEventRepository audits,
      UuidGenerator uuids) {
    this.taskCommands = taskCommands;
    this.tasks = tasks;
    this.candidates = candidates;
    this.executions = executions;
    this.events = events;
    this.nodes = nodes;
    this.routing = routing;
    this.lifecycle = lifecycle;
    this.participants = participants;
    this.audits = audits;
    this.uuids = uuids;
  }

  @Override
  public Optional<String> execute(
      TimeoutAction action,
      TaskExecution task,
      SlaExecution sla,
      Instant at,
      CorrelationId correlationId,
      CommandId commandId) {
    return switch (action) {
      case AUTO_REJECT -> Optional.of(autoReject(task, sla, correlationId, commandId));
      case GOTO_NODE -> Optional.of(gotoNode(task, sla, at, correlationId, commandId));
      case CREATE_MANUAL_TASK ->
          Optional.of(createManualTask(task, sla, at, correlationId, commandId));
      case FAIL_NODE -> Optional.of(failNode(task, sla, at, correlationId, commandId));
    };
  }

  private String autoReject(
      TaskExecution task,
      SlaExecution sla,
      CorrelationId correlationId,
      CommandId commandId) {
    var result =
        taskCommands.decideTaskByAutomation(
            task.getId(),
            BusinessOutcome.REJECTED,
            JsonNodeFactory.instance.objectNode(),
            slaReason(sla, "AUTO_REJECT"),
            correlationId,
            commandId,
            SLA_AUTOMATION_ACTOR,
            "workflow-sla-daemon");
    return "AUTO_REJECTED:" + result.task().getStatus().name();
  }

  private String gotoNode(
      TaskExecution task,
      SlaExecution sla,
      Instant at,
      CorrelationId correlationId,
      CommandId commandId) {
    NodeExecution execution = lockExecution(task);
    Event event = events.findById(execution.getEventId()).orElseThrow();
    UUID targetNodeId = resolveTargetNodeId(sla.getConfigSnapshotJson(), event);

    expire(task, at);
    ObjectNode output = JsonNodeFactory.instance.objectNode();
    output.put("decision", "SLA_TIMEOUT");
    output.put("targetNodeId", targetNodeId.toString());
    output.put("slaExecutionId", sla.getId().toString());
    execution.complete(timeoutPort(sla), output, at);
    executions.saveAndFlush(execution);
    audit(task, sla, "SLA_GOTO_NODE", output, correlationId, commandId, at);
    routing.route(execution.getId(), correlationId, commandId);
    lifecycle.syncEventStatus(execution.getEventId());
    return "GOTO_NODE:" + targetNodeId;
  }

  private String createManualTask(
      TaskExecution task,
      SlaExecution sla,
      Instant at,
      CorrelationId correlationId,
      CommandId commandId) {
    NodeExecution execution = lockExecution(task);
    expire(task, at);

    Optional<TaskExecution> existing =
        tasks.findAllByNodeExecutionIdOrderByCreatedAtAsc(execution.getId()).stream()
            .filter(candidate -> !candidate.getId().equals(task.getId()))
            .filter(candidate -> !TERMINAL_TASKS.contains(candidate.getStatus()))
            .findFirst();
    if (existing.isPresent()) {
      return "MANUAL_TASK:" + existing.orElseThrow().getId();
    }

    JsonNode manualConfig = manualTaskConfig(sla.getConfigSnapshotJson());
    ObjectNode fallback = JsonNodeFactory.instance.objectNode();
    fallback.put("type", "FIXED_USER");
    fallback.put("userId", currentOrCreator(task, execution).toString());
    List<UUID> resolved =
        participants.resolveAndSnapshot(
            task, manualConfig.path("participant"), fallback, "SLA_MANUAL_RECOVERY", at);

    UUID manualTaskId = uuids.generate();
    UUID assignee = resolved.size() == 1 ? resolved.getFirst() : null;
    TaskExecution manualTask =
        TaskExecution.create(
            manualTaskId,
            execution.getId(),
            task.getItemExecutionId(),
            assignee,
            manualConfig.path("title").asText("Manual SLA recovery"),
            manualConfig
                .path("description")
                .asText("Resolve workflow after SLA timeout " + sla.getId()),
            manualConfig.path("formSchema").isObject()
                ? manualConfig.path("formSchema")
                : task.getFormSchemaJson(),
            execution.getInputJson() == null
                ? JsonNodeFactory.instance.objectNode()
                : execution.getInputJson(),
            manualConfig.path("priority").asInt(90),
            null,
            at);
    tasks.saveAndFlush(manualTask);
    if (resolved.size() > 1) {
      for (UUID userId : resolved) {
        ObjectNode evidence = JsonNodeFactory.instance.objectNode();
        evidence.put("slaExecutionId", sla.getId().toString());
        evidence.put("role", "SLA_MANUAL_RECOVERY");
        candidates.save(
            TaskCandidate.create(manualTaskId, userId, "SLA_MANUAL_RECOVERY", evidence, at));
      }
      candidates.flush();
    }

    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("manualTaskId", manualTaskId.toString());
    metadata.put("replacesTimedOutTaskId", task.getId().toString());
    metadata.put("candidateCount", resolved.size());
    audit(task, sla, "SLA_MANUAL_TASK_CREATED", metadata, correlationId, commandId, at);
    return "MANUAL_TASK:" + manualTaskId;
  }

  private String failNode(
      TaskExecution task,
      SlaExecution sla,
      Instant at,
      CorrelationId correlationId,
      CommandId commandId) {
    NodeExecution execution = lockExecution(task);
    expire(task, at);
    ObjectNode error = JsonNodeFactory.instance.objectNode();
    error.put("code", "SLA_TIMEOUT");
    error.put("message", slaReason(sla, "FAIL_NODE"));
    error.put("slaExecutionId", sla.getId().toString());
    execution.fail(error, at);
    executions.saveAndFlush(execution);
    audit(task, sla, "SLA_NODE_FAILED", error, correlationId, commandId, at);
    lifecycle.syncEventStatus(execution.getEventId());
    return "NODE_FAILED:" + execution.getId();
  }

  private NodeExecution lockExecution(TaskExecution task) {
    return executions.findByIdForUpdate(task.getNodeExecutionId()).orElseThrow();
  }

  private void expire(TaskExecution task, Instant at) {
    if (!TERMINAL_TASKS.contains(task.getStatus())) {
      task.expire(BusinessOutcome.of("SLA_TIMEOUT"), at);
      tasks.saveAndFlush(task);
    }
  }

  private UUID currentOrCreator(TaskExecution task, NodeExecution execution) {
    if (task.getAssigneeId() != null) return task.getAssigneeId();
    return events.findById(execution.getEventId()).orElseThrow().getStartedBy();
  }

  private UUID resolveTargetNodeId(JsonNode config, Event event) {
    JsonNode actionConfig = config.path("timeoutActionConfig");
    String targetId =
        firstText(
            config.path("timeoutTargetNodeId"),
            actionConfig.path("targetNodeId"),
            config.path("gotoNode").path("targetNodeId"));
    if (targetId != null) {
      UUID id = UUID.fromString(targetId);
      NodeDefinition target = nodes.findById(id).orElseThrow();
      if (!target.getWorkflowVersionId().equals(event.getWorkflowVersionId())) {
        throw new IllegalStateException("SLA GOTO target is outside the Event WorkflowVersion");
      }
      return id;
    }
    String targetKey =
        firstText(
            config.path("timeoutTargetNodeKey"),
            actionConfig.path("targetNodeKey"),
            config.path("gotoNode").path("targetNodeKey"));
    if (targetKey == null) {
      throw new IllegalStateException("SLA GOTO_NODE requires a target node id or key");
    }
    return nodes
        .findByWorkflowVersionIdAndNodeKey(event.getWorkflowVersionId(), targetKey)
        .orElseThrow(() -> new IllegalStateException("SLA GOTO target node not found: " + targetKey))
        .getId();
  }

  private JsonNode manualTaskConfig(JsonNode config) {
    if (config.path("manualTask").isObject()) return config.path("manualTask");
    if (config.path("timeoutActionConfig").path("manualTask").isObject()) {
      return config.path("timeoutActionConfig").path("manualTask");
    }
    return JsonNodeFactory.instance.objectNode();
  }

  private String timeoutPort(SlaExecution sla) {
    return sla
        .getConfigSnapshotJson()
        .path("timeoutPort")
        .asText("TIMEOUT")
        .toUpperCase(Locale.ROOT);
  }

  private String firstText(JsonNode... values) {
    for (JsonNode value : values) {
      if (value != null && value.isTextual() && !value.asText().isBlank()) {
        return value.asText().trim();
      }
    }
    return null;
  }

  private void audit(
      TaskExecution task,
      SlaExecution sla,
      String action,
      ObjectNode details,
      CorrelationId correlationId,
      CommandId commandId,
      Instant at) {
    ObjectNode metadata = details.deepCopy();
    metadata.put("slaExecutionId", sla.getId().toString());
    metadata.put("taskId", task.getId().toString());
    metadata.put("nodeExecutionId", task.getNodeExecutionId().toString());
    audits.save(
        AuditEvent.record(
            uuids.generate(),
            "SLA_EXECUTION",
            sla.getId(),
            action,
            SLA_AUTOMATION_ACTOR,
            task.getAssigneeId(),
            correlationId,
            commandId,
            metadata,
            at));
  }

  private String slaReason(SlaExecution sla, String action) {
    return "SLA daemon timeout action "
        + action
        + " for slaExecution "
        + sla.getId()
        + " (due "
        + sla.getDueAt()
        + ")";
  }
}
