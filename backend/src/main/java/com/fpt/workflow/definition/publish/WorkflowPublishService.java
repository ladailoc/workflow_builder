package com.fpt.workflow.definition.publish;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.StaleDraftRevisionException;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.validation.ValidationCompilation;
import com.fpt.workflow.definition.validation.ValidationDefinition;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class WorkflowPublishService {

  private final WorkflowDefinitionRepository definitionRepository;
  private final WorkflowVersionRepository versionRepository;
  private final WorkflowValidationService validationService;
  private final ExecutionPackageCompiler packageCompiler;
  private final AuditEventRepository auditRepository;
  private final ActorContextProvider actorContextProvider;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;

  public WorkflowPublishService(
      WorkflowDefinitionRepository definitionRepository,
      WorkflowVersionRepository versionRepository,
      WorkflowValidationService validationService,
      ExecutionPackageCompiler packageCompiler,
      AuditEventRepository auditRepository,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock) {
    this.definitionRepository = definitionRepository;
    this.versionRepository = versionRepository;
    this.validationService = validationService;
    this.packageCompiler = packageCompiler;
    this.auditRepository = auditRepository;
    this.actorContextProvider = actorContextProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public PublishResult publish(
      UUID workflowVersionId,
      ExpectedVersion expectedVersion,
      long expectedRevision,
      CommandId commandId) {
    WorkflowVersion draft =
        versionRepository
            .findByIdForUpdate(workflowVersionId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "WORKFLOW_VERSION_NOT_FOUND", "WorkflowVersion was not found"));
    WorkflowDefinition definition =
        definitionRepository
            .findByIdForUpdate(draft.getDefinitionId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "WORKFLOW_DEFINITION_NOT_FOUND", "WorkflowDefinition was not found"));
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(draft.getLockVersion()), expectedVersion);
    try {
      draft.requireDraft();
    } catch (IllegalStateException exception) {
      throw new CommandConflictException(
          "WORKFLOW_VERSION_NOT_PUBLISHABLE", exception.getMessage());
    }
    if (draft.getRevision() != expectedRevision) {
      throw new CommandConflictException(
          "WORKFLOW_DRAFT_REVISION_CONFLICT", "Draft revision changed before publish");
    }

    // Always compile current normalized rows inside this transaction; stale ValidationRun is
    // ignored.
    ValidationCompilation validation = validationService.compileCurrent(workflowVersionId);
    if (!validation.publishable()) {
      throw new UnprocessableCommandException(
          "WORKFLOW_VALIDATION_FAILED", "Current Draft failed full server-side validation");
    }
    ValidationDefinition snapshot = validationService.loadCurrent(workflowVersionId);
    ExecutionPackageCompiler.CompiledExecutionPackage executionPackage =
        packageCompiler.compile(snapshot);

    Instant now = clock.now();
    versionRepository
        .findByDefinitionIdAndStatus(definition.getId(), WorkflowVersionStatus.PUBLISHED)
        .ifPresent(
            previous -> {
              previous.supersede();
              versionRepository.saveAndFlush(previous);
            });
    try {
      draft.publish(
          expectedRevision,
          executionPackage.checksum(),
          executionPackage.json(),
          actorContextProvider.requireActor().actorId(),
          now);
    } catch (StaleDraftRevisionException exception) {
      throw new CommandConflictException(
          "WORKFLOW_DRAFT_REVISION_CONFLICT", exception.getMessage());
    }
    versionRepository.saveAndFlush(draft);
    definition.publishVersion(draft.getId(), now);
    definitionRepository.saveAndFlush(definition);
    recordAudit(draft, commandId, now, executionPackage.checksum());
    return new PublishResult(
        draft.getId(), draft.getVersionNo(), executionPackage.checksum(), draft.getStatus());
  }

  private void recordAudit(
      WorkflowVersion version, CommandId commandId, Instant now, String checksum) {
    ActorContext actor = actorContextProvider.requireActor();
    ObjectNode metadata = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    metadata.put("definitionId", version.getDefinitionId().toString());
    metadata.put("versionNo", version.getVersionNo());
    metadata.put("checksum", checksum);
    auditRepository.save(
        AuditEvent.record(
            uuidGenerator.generate(),
            "WORKFLOW_VERSION",
            version.getId(),
            "WORKFLOW_VERSION_PUBLISHED",
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
        // Fall through to a server-generated correlation identifier.
      }
    }
    return CorrelationId.generate(uuidGenerator);
  }

  public record PublishResult(
      UUID workflowVersionId, int versionNo, String checksum, WorkflowVersionStatus status) {}
}
