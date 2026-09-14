package com.fpt.workflow.definition.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.dto.RequestTypeDtos;
import com.fpt.workflow.definition.dto.WorkflowDefinitionDtos;
import com.fpt.workflow.definition.dto.WorkflowGraphDtos;
import com.fpt.workflow.definition.dto.WorkflowManagementDtos;
import com.fpt.workflow.definition.dto.WorkflowVersionDtos;
import com.fpt.workflow.definition.governance.WorkflowRollbackService;
import com.fpt.workflow.definition.publish.WorkflowPublishService;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.operations.command.CommandCompletion;
import com.fpt.workflow.operations.command.CommandExecutor;
import com.fpt.workflow.operations.command.CommandInvocation;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.time.PlatformClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** Idempotent command boundary used by workflow and RequestType management APIs. */
@Service
public class DefinitionManagementCommandFacade {

  private final CommandExecutor commandExecutor;
  private final WorkflowDefinitionService definitionService;
  private final WorkflowVersionService versionService;
  private final WorkflowGraphService graphService;
  private final WorkflowFormService formService;
  private final WorkflowPublishService publishService;
  private final WorkflowRollbackService rollbackService;
  private final WorkflowLifecycleService lifecycleService;
  private final RequestTypeService requestTypeService;
  private final AuditEventRepository auditRepository;
  private final ActorContextProvider actorContextProvider;
  private final ObjectMapper objectMapper;
  private final CanonicalDefinitionJson canonicalJson;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public DefinitionManagementCommandFacade(
      CommandExecutor commandExecutor,
      WorkflowDefinitionService definitionService,
      WorkflowVersionService versionService,
      WorkflowGraphService graphService,
      WorkflowFormService formService,
      WorkflowPublishService publishService,
      WorkflowRollbackService rollbackService,
      WorkflowLifecycleService lifecycleService,
      RequestTypeService requestTypeService,
      AuditEventRepository auditRepository,
      ActorContextProvider actorContextProvider,
      ObjectMapper objectMapper,
      CanonicalDefinitionJson canonicalJson,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.commandExecutor = commandExecutor;
    this.definitionService = definitionService;
    this.versionService = versionService;
    this.graphService = graphService;
    this.formService = formService;
    this.publishService = publishService;
    this.rollbackService = rollbackService;
    this.lifecycleService = lifecycleService;
    this.requestTypeService = requestTypeService;
    this.auditRepository = auditRepository;
    this.actorContextProvider = actorContextProvider;
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
    this.uuids = uuids;
    this.clock = clock;
  }

  public WorkflowDefinitionDtos.View createWorkflow(
      CommandId commandId, WorkflowDefinitionDtos.Create request) {
    UUID scopeId = actorContextProvider.requireActor().actorId();
    return execute(
        "WORKFLOW_CATALOG",
        scopeId,
        commandId,
        "WORKFLOW_CREATE",
        null,
        request,
        WorkflowDefinitionDtos.View.class,
        () -> {
          WorkflowDefinitionDtos.View created = definitionService.create(request);
          recordAudit("WORKFLOW_DEFINITION", created.id(), "WORKFLOW_CREATED", commandId, null);
          return created;
        });
  }

  public WorkflowDefinitionDtos.View updateWorkflow(
      UUID workflowId,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      WorkflowManagementDtos.UpdateWorkflow request) {
    return execute(
        "WORKFLOW_DEFINITION",
        workflowId,
        commandId,
        "WORKFLOW_UPDATE",
        expectedVersion.value(),
        request,
        WorkflowDefinitionDtos.View.class,
        () -> {
          WorkflowDefinitionDtos.View current = definitionService.get(workflowId);
          WorkflowDefinitionDtos.View updated =
              definitionService.update(
                  workflowId,
                  expectedVersion,
                  new WorkflowDefinitionDtos.Update(
                      request.name(),
                      request.description(),
                      request.ownerId(),
                      current.lifecycle()));
          recordAudit("WORKFLOW_DEFINITION", workflowId, "WORKFLOW_UPDATED", commandId, null);
          return updated;
        });
  }

  public WorkflowVersionDtos.View createDraft(
      UUID workflowId, CommandId commandId, UUID basedOnVersionId, UUID rollbackOfVersionId) {
    WorkflowVersionDtos.CreateDraft request =
        new WorkflowVersionDtos.CreateDraft(workflowId, basedOnVersionId, rollbackOfVersionId);
    return execute(
        "WORKFLOW_DEFINITION",
        workflowId,
        commandId,
        "WORKFLOW_DRAFT_CREATE",
        null,
        request,
        WorkflowVersionDtos.View.class,
        () -> {
          WorkflowVersionDtos.View created = versionService.createDraft(request);
          recordAudit("WORKFLOW_VERSION", created.id(), "WORKFLOW_DRAFT_CREATED", commandId, null);
          return created;
        });
  }

  public WorkflowGraphDtos.GraphView saveGraph(
      UUID workflowId,
      UUID versionId,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      WorkflowManagementDtos.SaveGraph request) {
    requireVersion(workflowId, versionId);
    return execute(
        "WORKFLOW_VERSION",
        versionId,
        commandId,
        "WORKFLOW_GRAPH_SAVE",
        expectedVersion.value(),
        request,
        WorkflowGraphDtos.GraphView.class,
        () -> {
          WorkflowGraphDtos.GraphView saved =
              graphService.replaceGraph(
                  versionId, request.graph(), expectedVersion, request.expectedRevision());
          recordAudit("WORKFLOW_VERSION", versionId, "WORKFLOW_DRAFT_SAVED", commandId, null);
          return saved;
        });
  }

  public WorkflowFormService.FormMutation saveTicketForm(
      UUID workflowId,
      UUID versionId,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      WorkflowManagementDtos.SaveTicketForm request) {
    requireVersion(workflowId, versionId);
    return execute(
        "WORKFLOW_VERSION",
        versionId,
        commandId,
        "WORKFLOW_TICKET_FORM_SAVE",
        expectedVersion.value(),
        request,
        WorkflowFormService.FormMutation.class,
        () -> {
          WorkflowFormService.FormMutation saved =
              formService.saveTicketForm(
                  versionId, request.schemaJson(), expectedVersion, request.expectedRevision());
          recordAudit("WORKFLOW_VERSION", versionId, "WORKFLOW_DRAFT_SAVED", commandId, null);
          return saved;
        });
  }

  public WorkflowPublishService.PublishResult publish(
      UUID workflowId,
      UUID versionId,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      long expectedRevision) {
    return publish(
        workflowId, versionId, commandId, expectedVersion, expectedRevision, java.util.Set.of());
  }

  public WorkflowPublishService.PublishResult publish(
      UUID workflowId,
      UUID versionId,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      long expectedRevision,
      java.util.Set<String> acknowledgedWarnings) {
    requireVersion(workflowId, versionId);
    WorkflowManagementDtos.PublishCommand request =
        new WorkflowManagementDtos.PublishCommand(expectedRevision, acknowledgedWarnings);
    return execute(
        "WORKFLOW_VERSION",
        versionId,
        commandId,
        "WORKFLOW_PUBLISH",
        expectedVersion.value(),
        request,
        WorkflowPublishService.PublishResult.class,
        () ->
            publishService.publish(
                versionId, expectedVersion, expectedRevision, commandId, acknowledgedWarnings));
  }

  public WorkflowRollbackService.CloneDraftResult cloneAsDraft(
      UUID workflowId,
      UUID sourceVersionId,
      CommandId commandId,
      ExpectedVersion expectedDefinitionVersion) {
    requireVersion(workflowId, sourceVersionId);
    return execute(
        "WORKFLOW_DEFINITION",
        workflowId,
        commandId,
        "WORKFLOW_CLONE_AS_DRAFT",
        expectedDefinitionVersion.value(),
        sourceVersionId,
        WorkflowRollbackService.CloneDraftResult.class,
        () -> rollbackService.cloneAsDraft(sourceVersionId, expectedDefinitionVersion, commandId));
  }

  public WorkflowDefinitionDtos.View lifecycle(
      UUID workflowId,
      String action,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      String reason) {
    return execute(
        "WORKFLOW_DEFINITION",
        workflowId,
        commandId,
        "WORKFLOW_" + action,
        expectedVersion.value(),
        new WorkflowManagementDtos.LifecycleCommand(reason),
        WorkflowDefinitionDtos.View.class,
        () ->
            switch (action) {
              case "SUSPEND" ->
                  lifecycleService.suspend(workflowId, expectedVersion, commandId, reason);
              case "REACTIVATE" ->
                  lifecycleService.reactivate(workflowId, expectedVersion, commandId, reason);
              case "ARCHIVE" ->
                  lifecycleService.archive(workflowId, expectedVersion, commandId, reason);
              default -> throw new IllegalArgumentException("Unsupported lifecycle command");
            });
  }

  public RequestTypeDtos.View createRequestType(
      CommandId commandId, RequestTypeDtos.Create request) {
    UUID scopeId = actorContextProvider.requireActor().actorId();
    return execute(
        "REQUEST_TYPE_CATALOG",
        scopeId,
        commandId,
        "REQUEST_TYPE_CREATE",
        null,
        request,
        RequestTypeDtos.View.class,
        () -> {
          RequestTypeDtos.View created = requestTypeService.create(request);
          recordAudit("REQUEST_TYPE", created.id(), "REQUEST_TYPE_CREATED", commandId, null);
          return created;
        });
  }

  public RequestTypeDtos.View updateRequestType(
      UUID requestTypeId,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      WorkflowManagementDtos.UpdateRequestType request) {
    return execute(
        "REQUEST_TYPE",
        requestTypeId,
        commandId,
        "REQUEST_TYPE_UPDATE",
        expectedVersion.value(),
        request,
        RequestTypeDtos.View.class,
        () -> {
          RequestTypeDtos.View current = requestTypeService.get(requestTypeId);
          RequestTypeDtos.View updated =
              requestTypeService.update(
                  requestTypeId,
                  expectedVersion,
                  new RequestTypeDtos.Update(
                      request.name(),
                      request.description(),
                      request.category(),
                      request.workflowDefinitionId(),
                      current.active(),
                      request.creationPolicyJson()));
          recordAudit("REQUEST_TYPE", requestTypeId, "REQUEST_TYPE_UPDATED", commandId, null);
          return updated;
        });
  }

  public RequestTypeDtos.View setRequestTypeActive(
      UUID requestTypeId,
      boolean active,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      String reason) {
    String eventType = active ? "REQUEST_TYPE_ACTIVATED" : "REQUEST_TYPE_DEACTIVATED";
    return execute(
        "REQUEST_TYPE",
        requestTypeId,
        commandId,
        eventType,
        expectedVersion.value(),
        new WorkflowManagementDtos.RequestTypeActivation(reason),
        RequestTypeDtos.View.class,
        () -> {
          RequestTypeDtos.View current = requestTypeService.get(requestTypeId);
          RequestTypeDtos.View updated =
              requestTypeService.update(
                  requestTypeId,
                  expectedVersion,
                  new RequestTypeDtos.Update(
                      current.name(),
                      current.description(),
                      current.category(),
                      current.workflowDefinitionId(),
                      active,
                      current.creationPolicyJson()));
          recordAudit("REQUEST_TYPE", requestTypeId, eventType, commandId, reason);
          return updated;
        });
  }

  private void requireVersion(UUID workflowId, UUID versionId) {
    WorkflowVersionDtos.View version = versionService.get(versionId);
    if (!version.definitionId().equals(workflowId)) {
      throw new com.fpt.workflow.shared.api.CommandConflictException(
          "WORKFLOW_VERSION_DEFINITION_MISMATCH",
          "Workflow version does not belong to the requested definition");
    }
  }

  private <T> T execute(
      String scopeType,
      UUID scopeId,
      CommandId commandId,
      String commandType,
      Long expectedVersion,
      Object request,
      Class<T> resultType,
      Supplier<T> action) {
    var result =
        commandExecutor.execute(
            new CommandInvocation(
                scopeType, scopeId, commandId, commandType, expectedVersion, hash(request)),
            () ->
                new CommandCompletion(
                    objectMapper.valueToTree(action.get()), JsonNodeFactory.instance.objectNode()));
    try {
      return objectMapper.treeToValue(result.resultJson(), resultType);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Stored management command result cannot be decoded", exception);
    }
  }

  private String hash(Object request) {
    JsonNode canonical = canonicalJson.canonicalize(objectMapper.valueToTree(request));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }

  private void recordAudit(
      String aggregateType,
      UUID aggregateId,
      String eventType,
      CommandId commandId,
      String reason) {
    ActorContext actor = actorContextProvider.requireActor();
    Instant now = clock.now();
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    if (reason != null && !reason.isBlank()) {
      metadata.put("reason", reason.trim());
    }
    auditRepository.save(
        AuditEvent.record(
            uuids.generate(),
            aggregateType,
            aggregateId,
            eventType,
            actor.actorId(),
            actor.actorId(),
            correlationId(),
            commandId,
            metadata,
            now));
  }

  private CorrelationId correlationId() {
    String value = MDC.get("correlationId");
    if (value != null) {
      try {
        return CorrelationId.parse(value);
      } catch (IllegalArgumentException ignored) {
        // Generate a trusted server correlation id below.
      }
    }
    return CorrelationId.generate(uuids);
  }
}
