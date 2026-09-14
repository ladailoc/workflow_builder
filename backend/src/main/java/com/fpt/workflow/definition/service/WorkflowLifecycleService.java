package com.fpt.workflow.definition.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.dto.WorkflowDefinitionDtos;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Explicit, audited WorkflowDefinition lifecycle commands. */
@Service
public class WorkflowLifecycleService {

  private final WorkflowDefinitionRepository definitionRepository;
  private final AuditEventRepository auditRepository;
  private final ActorContextProvider actorContextProvider;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public WorkflowLifecycleService(
      WorkflowDefinitionRepository definitionRepository,
      AuditEventRepository auditRepository,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.definitionRepository = definitionRepository;
    this.auditRepository = auditRepository;
    this.actorContextProvider = actorContextProvider;
    this.uuids = uuids;
    this.clock = clock;
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View suspend(
      UUID workflowId, ExpectedVersion expectedVersion, CommandId commandId, String reason) {
    return transition(
        workflowId,
        expectedVersion,
        commandId,
        reason,
        WorkflowDefinitionLifecycle.SUSPENDED,
        "WORKFLOW_SUSPENDED");
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View reactivate(
      UUID workflowId, ExpectedVersion expectedVersion, CommandId commandId, String reason) {
    return transition(
        workflowId,
        expectedVersion,
        commandId,
        reason,
        WorkflowDefinitionLifecycle.ACTIVE,
        "WORKFLOW_REACTIVATED");
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View archive(
      UUID workflowId, ExpectedVersion expectedVersion, CommandId commandId, String reason) {
    return transition(
        workflowId,
        expectedVersion,
        commandId,
        reason,
        WorkflowDefinitionLifecycle.ARCHIVED,
        "WORKFLOW_ARCHIVED");
  }

  private WorkflowDefinitionDtos.View transition(
      UUID workflowId,
      ExpectedVersion expectedVersion,
      CommandId commandId,
      String reason,
      WorkflowDefinitionLifecycle target,
      String eventType) {
    WorkflowDefinition definition =
        definitionRepository
            .findByIdForUpdate(workflowId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition was not found"));
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(definition.getLockVersion()), expectedVersion);
    WorkflowDefinitionLifecycle current = definition.getLifecycle();
    if (!allowedTargets(current).contains(target)) {
      throw new CommandConflictException(
          "WORKFLOW_LIFECYCLE_CONFLICT", "Workflow lifecycle transition is not allowed");
    }
    if (target == WorkflowDefinitionLifecycle.ARCHIVED
        && definition.getActiveDraftVersionId() != null) {
      throw new CommandConflictException(
          "ACTIVE_DRAFT_EXISTS", "Archive the active Draft version before archiving the workflow");
    }
    Instant now = clock.now();
    definition.updateDetails(
        definition.getName(), definition.getDescription(), definition.getOwnerId(), target, now);
    WorkflowDefinition saved = definitionRepository.saveAndFlush(definition);
    recordAudit(saved, current, target, eventType, commandId, reason, now);
    return WorkflowDefinitionDtos.View.from(saved);
  }

  private EnumSet<WorkflowDefinitionLifecycle> allowedTargets(WorkflowDefinitionLifecycle current) {
    return switch (current) {
      case ACTIVE ->
          EnumSet.of(WorkflowDefinitionLifecycle.SUSPENDED, WorkflowDefinitionLifecycle.ARCHIVED);
      case SUSPENDED ->
          EnumSet.of(WorkflowDefinitionLifecycle.ACTIVE, WorkflowDefinitionLifecycle.ARCHIVED);
      case ARCHIVED -> EnumSet.noneOf(WorkflowDefinitionLifecycle.class);
    };
  }

  private void recordAudit(
      WorkflowDefinition definition,
      WorkflowDefinitionLifecycle previous,
      WorkflowDefinitionLifecycle target,
      String eventType,
      CommandId commandId,
      String reason,
      Instant now) {
    ActorContext actor = actorContextProvider.requireActor();
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("previousLifecycle", previous.name());
    metadata.put("lifecycle", target.name());
    if (reason != null && !reason.isBlank()) {
      metadata.put("reason", reason.trim());
    }
    auditRepository.save(
        AuditEvent.record(
            uuids.generate(),
            "WORKFLOW_DEFINITION",
            definition.getId(),
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
