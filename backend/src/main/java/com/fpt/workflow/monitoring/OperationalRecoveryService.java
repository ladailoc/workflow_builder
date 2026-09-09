package com.fpt.workflow.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.connector.domain.ConnectorActionVersion;
import com.fpt.workflow.connector.repository.ConnectorActionVersionRepository;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.integration.service.SystemActionTransactionService;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.operations.command.CommandCompletion;
import com.fpt.workflow.operations.command.CommandExecutionResult;
import com.fpt.workflow.operations.command.CommandExecutor;
import com.fpt.workflow.operations.command.CommandInvocation;
import com.fpt.workflow.operations.job.WorkflowJob;
import com.fpt.workflow.operations.job.WorkflowJobRepository;
import com.fpt.workflow.operations.job.WorkflowJobStatus;
import com.fpt.workflow.operations.job.WorkflowJobTransactions;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.lifecycle.EventLifecycleService;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskExecution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Audited, idempotent operational overrides. Every method is terminal-wins safe. */
@Service
public class OperationalRecoveryService {

  private static final Set<EventStatus> TERMINAL_EVENTS =
      EnumSet.of(
          EventStatus.COMPLETED, EventStatus.FAILED, EventStatus.CANCELLED, EventStatus.TERMINATED);

  private final CommandExecutor commands;
  private final WorkflowJobRepository jobs;
  private final WorkflowJobTransactions jobTransactions;
  private final NodeExecutionRepository nodes;
  private final NodeActivationService activations;
  private final IntegrationExecutionRepository integrations;
  private final ConnectorActionVersionRepository actionVersions;
  private final SystemActionTransactionService systemActionTransactions;
  private final ManualRecoveryTaskService manualTasks;
  private final EventRepository events;
  private final EventLifecycleService lifecycle;
  private final AuditEventRepository audits;
  private final ActorContextProvider actors;
  private final UuidGenerator uuids;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;
  private final CanonicalDefinitionJson canonicalJson;

  public OperationalRecoveryService(
      CommandExecutor commands,
      WorkflowJobRepository jobs,
      WorkflowJobTransactions jobTransactions,
      NodeExecutionRepository nodes,
      NodeActivationService activations,
      IntegrationExecutionRepository integrations,
      ConnectorActionVersionRepository actionVersions,
      SystemActionTransactionService systemActionTransactions,
      ManualRecoveryTaskService manualTasks,
      EventRepository events,
      EventLifecycleService lifecycle,
      AuditEventRepository audits,
      ActorContextProvider actors,
      UuidGenerator uuids,
      PlatformClock clock,
      ObjectMapper objectMapper,
      CanonicalDefinitionJson canonicalJson) {
    this.commands = commands;
    this.jobs = jobs;
    this.jobTransactions = jobTransactions;
    this.nodes = nodes;
    this.activations = activations;
    this.integrations = integrations;
    this.actionVersions = actionVersions;
    this.systemActionTransactions = systemActionTransactions;
    this.manualTasks = manualTasks;
    this.events = events;
    this.lifecycle = lifecycle;
    this.audits = audits;
    this.actors = actors;
    this.uuids = uuids;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
  }

  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public CommandExecutionResult retryJob(
      UUID id, long expectedVersion, OverrideCommand request, CorrelationId correlationId) {
    return execute(
        "WORKFLOW_JOB",
        id,
        "OPERATOR_RETRY_JOB",
        expectedVersion,
        request,
        () -> {
          WorkflowJob job =
              jobs.findById(id)
                  .orElseThrow(
                      () -> new ResourceNotFoundException("JOB_NOT_FOUND", "Job was not found"));
          if (job.getStatus() != WorkflowJobStatus.DEAD
              || !jobTransactions.retryDead(id, expectedVersion)) {
            throw conflict("JOB_NOT_RETRYABLE", "Only the current DEAD job can be retried");
          }
          audit("WORKFLOW_JOB", id, "JOB_RETRY_REQUESTED", request, correlationId);
          return result("jobId", id, "status", "RETRY");
        });
  }

  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public CommandExecutionResult retryNode(
      UUID id, long expectedVersion, OverrideCommand request, CorrelationId correlationId) {
    return execute(
        "NODE_EXECUTION",
        id,
        "OPERATOR_RETRY_NODE",
        expectedVersion,
        request,
        () -> {
          NodeExecution retry =
              activations.retry(
                  id, expectedVersion, correlationId, new CommandId(request.commandId()));
          audit("NODE_EXECUTION", id, "NODE_RETRY_REQUESTED", request, correlationId);
          return result("nodeExecutionId", retry.getId(), "status", retry.getStatus().name());
        });
  }

  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public CommandExecutionResult retryIntegration(
      UUID id, long expectedVersion, OverrideCommand request, CorrelationId correlationId) {
    return execute(
        "INTEGRATION_EXECUTION",
        id,
        "OPERATOR_RETRY_INTEGRATION",
        expectedVersion,
        request,
        () -> {
          IntegrationExecution integration = requireIntegration(id);
          if (integration.getLockVersion() != expectedVersion
              || integration.getStatus() != IntegrationExecutionStatus.FAILED) {
            throw conflict(
                "INTEGRATION_NOT_RETRYABLE", "Only the current FAILED integration can be retried");
          }
          ConnectorActionVersion action =
              actionVersions.findById(integration.getConnectorActionVersionId()).orElseThrow();
          if (!action.getIdempotencyPolicyJson().path("idempotent").asBoolean(true)) {
            throw conflict(
                "NON_IDEMPOTENT_RETRY_FORBIDDEN",
                "Non-idempotent integration requires manual reconciliation");
          }
          NodeExecution failedNode = nodes.findById(integration.getNodeExecutionId()).orElseThrow();
          if (failedNode.getStatus() != NodeExecutionStatus.FAILED) {
            throw conflict(
                "INTEGRATION_ALREADY_ROUTED",
                "The integration node is not failed; retry would duplicate continuation");
          }
          Event event = events.findById(integration.getEventId()).orElseThrow();
          requireNonTerminal(event);
          NodeExecution retry =
              activations.retry(
                  failedNode.getId(),
                  failedNode.getLockVersion(),
                  correlationId,
                  new CommandId(request.commandId()));
          audit("INTEGRATION_EXECUTION", id, "INTEGRATION_RETRY_REQUESTED", request, correlationId);
          return result("nodeExecutionId", retry.getId(), "status", retry.getStatus().name());
        });
  }

  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public CommandExecutionResult resolveIntegration(
      UUID id, long expectedVersion, OverrideCommand request, CorrelationId correlationId) {
    return execute(
        "INTEGRATION_EXECUTION",
        id,
        "OPERATOR_RESOLVE_INTEGRATION",
        expectedVersion,
        request,
        () -> {
          if (request.outcomePort() == null || request.outcomePort().isBlank()) {
            throw new IllegalArgumentException("outcomePort is required for manual resolution");
          }
          var resolved =
              systemActionTransactions.resolveManualReconciliationTx(
                  id,
                  expectedVersion,
                  request.outcomePort().trim().toUpperCase(java.util.Locale.ROOT),
                  request.output(),
                  correlationId,
                  new CommandId(request.commandId()));
          audit("INTEGRATION_EXECUTION", id, "MANUAL_RECOVERY", request, correlationId);
          return result("nodeExecutionId", resolved.nodeExecution().getId(), "status", "RESOLVED");
        });
  }

  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public CommandExecutionResult createManualTask(
      UUID id, long expectedVersion, OverrideCommand request, CorrelationId correlationId) {
    return execute(
        "INTEGRATION_EXECUTION",
        id,
        "OPERATOR_CREATE_MANUAL_TASK",
        expectedVersion,
        request,
        () -> {
          IntegrationExecution integration = requireIntegration(id);
          if (integration.getLockVersion() != expectedVersion) {
            throw conflict("STALE_EXPECTED_VERSION", "Integration version does not match If-Match");
          }
          TaskExecution task = manualTasks.create(id);
          audit(
              "INTEGRATION_EXECUTION", id, "MANUAL_RECOVERY_TASK_CREATED", request, correlationId);
          return result("taskId", task.getId(), "status", task.getStatus().name());
        });
  }

  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public CommandExecutionResult terminateEvent(
      UUID id, long expectedVersion, OverrideCommand request, CorrelationId correlationId) {
    return execute(
        "EVENT",
        id,
        "OPERATOR_TERMINATE_EVENT",
        expectedVersion,
        request,
        () -> {
          Event terminated =
              lifecycle.terminateEvent(
                  id,
                  new CommandId(request.commandId()),
                  correlationId,
                  request.reason(),
                  expectedVersion);
          return result("eventId", terminated.getId(), "status", terminated.getStatus().name());
        });
  }

  private CommandExecutionResult execute(
      String scope,
      UUID id,
      String type,
      long expectedVersion,
      OverrideCommand request,
      Supplier<ObjectNode> action) {
    request.requireReason();
    return commands.execute(
        new CommandInvocation(
            scope,
            id,
            new CommandId(request.commandId()),
            type,
            expectedVersion,
            canonicalJson.checksum(canonicalJson.canonicalize(objectMapper.valueToTree(request)))),
        () -> new CommandCompletion(action.get(), objectMapper.createObjectNode()));
  }

  private void audit(
      String aggregateType,
      UUID aggregateId,
      String eventType,
      OverrideCommand request,
      CorrelationId correlationId) {
    ActorContext actor = actors.requireActor();
    ObjectNode metadata = objectMapper.createObjectNode();
    metadata.put("reason", request.reason().trim());
    if (request.outcomePort() != null) metadata.put("outcomePort", request.outcomePort());
    audits.save(
        AuditEvent.record(
            uuids.generate(),
            aggregateType,
            aggregateId,
            eventType,
            actor.actorId(),
            actor.actorId(),
            correlationId,
            new CommandId(request.commandId()),
            metadata,
            clock.now()));
  }

  private IntegrationExecution requireIntegration(UUID id) {
    return integrations
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "INTEGRATION_EXECUTION_NOT_FOUND", "Integration execution was not found"));
  }

  private void requireNonTerminal(Event event) {
    if (TERMINAL_EVENTS.contains(event.getStatus())) {
      throw conflict("TERMINAL_EVENT_WINS", "A terminal Event cannot be recovered in place");
    }
  }

  private ObjectNode result(String idField, UUID id, String stateField, String state) {
    ObjectNode json = objectMapper.createObjectNode();
    json.put(idField, id.toString());
    json.put(stateField, state);
    return json;
  }

  private CommandConflictException conflict(String code, String message) {
    return new CommandConflictException(code, message);
  }

  public record OverrideCommand(
      @NotNull UUID commandId, @NotBlank String reason, String outcomePort, JsonNode output) {
    public OverrideCommand {
      if (commandId == null) throw new IllegalArgumentException("commandId is required");
      output = output == null ? JsonNodeFactory.instance.objectNode() : output.deepCopy();
      if (!output.isObject()) throw new IllegalArgumentException("output must be an object");
    }

    void requireReason() {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("Operator override reason is mandatory");
      }
    }

    @Override
    public JsonNode output() {
      return output.deepCopy();
    }
  }
}
