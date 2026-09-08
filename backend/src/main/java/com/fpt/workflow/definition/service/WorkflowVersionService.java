package com.fpt.workflow.definition.service;

import com.fpt.workflow.definition.domain.StaleDraftRevisionException;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.dto.WorkflowVersionDtos;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.domain.page.PageRequest;
import com.fpt.workflow.shared.domain.page.PageResult;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class WorkflowVersionService {

  private static final Map<String, String> SORT_PROPERTIES =
      Map.of(
          "versionNo", "versionNo",
          "status", "status",
          "createdAt", "createdAt",
          "publishedAt", "publishedAt");

  private final WorkflowVersionRepository workflowVersionRepository;
  private final WorkflowDefinitionRepository workflowDefinitionRepository;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ActorContextProvider actorContextProvider;

  public WorkflowVersionService(
      WorkflowVersionRepository workflowVersionRepository,
      WorkflowDefinitionRepository workflowDefinitionRepository,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ActorContextProvider actorContextProvider) {
    this.workflowVersionRepository = workflowVersionRepository;
    this.workflowDefinitionRepository = workflowDefinitionRepository;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.actorContextProvider = actorContextProvider;
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public WorkflowVersionDtos.View createDraft(WorkflowVersionDtos.CreateDraft request) {
    WorkflowDefinition definition = requireDefinitionForUpdate(request.definitionId());
    if (definition.getActiveDraftVersionId() != null) {
      throw new CommandConflictException(
          "ACTIVE_DRAFT_EXISTS", "Workflow definition already has an active draft");
    }
    requireSameDefinition(request.basedOnVersionId(), request.definitionId(), "basedOnVersionId");
    requireSameDefinition(
        request.rollbackOfVersionId(), request.definitionId(), "rollbackOfVersionId");

    int versionNo =
        Math.addExact(
            workflowVersionRepository.findMaxVersionNoByDefinitionId(request.definitionId()), 1);
    UUID versionId = uuidGenerator.generate();
    WorkflowVersion version =
        WorkflowVersion.createDraft(
            versionId,
            request.definitionId(),
            versionNo,
            request.basedOnVersionId(),
            request.rollbackOfVersionId(),
            actorContextProvider.requireActor().actorId(),
            clock.now());
    WorkflowVersion saved = workflowVersionRepository.saveAndFlush(version);
    definition.assignActiveDraft(versionId, clock.now());
    workflowDefinitionRepository.saveAndFlush(definition);
    return WorkflowVersionDtos.View.from(saved);
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public WorkflowVersionDtos.View updateDraft(
      UUID id, ExpectedVersion expectedVersion, WorkflowVersionDtos.UpdateDraft request) {
    WorkflowVersion version = requireById(id);
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(version.getLockVersion()), expectedVersion);
    try {
      version.updateDraft(
          request.expectedRevision(), request.checksum(), request.executionPackageJson());
    } catch (StaleDraftRevisionException exception) {
      throw new CommandConflictException(
          "WORKFLOW_DRAFT_REVISION_CONFLICT", exception.getMessage());
    } catch (IllegalStateException exception) {
      throw new CommandConflictException("WORKFLOW_VERSION_NOT_EDITABLE", exception.getMessage());
    }
    return WorkflowVersionDtos.View.from(workflowVersionRepository.saveAndFlush(version));
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public void deleteDraft(UUID id, ExpectedVersion expectedVersion) {
    WorkflowVersion version = requireById(id);
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(version.getLockVersion()), expectedVersion);
    try {
      version.requireDraft();
    } catch (IllegalStateException exception) {
      throw new CommandConflictException("WORKFLOW_VERSION_NOT_EDITABLE", exception.getMessage());
    }
    WorkflowDefinition definition = requireDefinition(version.getDefinitionId());
    definition.clearActiveDraft(version.getId(), clock.now());
    workflowDefinitionRepository.saveAndFlush(definition);
    workflowVersionRepository.delete(version);
    workflowVersionRepository.flush();
  }

  @TransactionalQuery
  public WorkflowVersionDtos.View get(UUID id) {
    return WorkflowVersionDtos.View.from(requireById(id));
  }

  @TransactionalQuery
  public WorkflowVersionDtos.View get(UUID definitionId, int versionNo) {
    return workflowVersionRepository
        .findByDefinitionIdAndVersionNo(definitionId, versionNo)
        .map(WorkflowVersionDtos.View::from)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_VERSION_NOT_FOUND", "Workflow version was not found"));
  }

  @TransactionalQuery
  public PageResult<WorkflowVersionDtos.View> listByDefinition(
      UUID definitionId, PageRequest request) {
    requireDefinition(definitionId);
    var page =
        workflowVersionRepository.findAllByDefinitionId(
            definitionId, DefinitionPageables.toSpring(request, SORT_PROPERTIES));
    return new PageResult<>(
        page.getContent().stream().map(WorkflowVersionDtos.View::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements());
  }

  private WorkflowVersion requireById(UUID id) {
    return workflowVersionRepository
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_VERSION_NOT_FOUND", "Workflow version was not found"));
  }

  private WorkflowDefinition requireDefinition(UUID id) {
    return workflowDefinitionRepository
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition was not found"));
  }

  private WorkflowDefinition requireDefinitionForUpdate(UUID id) {
    return workflowDefinitionRepository
        .findByIdForUpdate(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition was not found"));
  }

  private void requireSameDefinition(UUID versionId, UUID definitionId, String field) {
    if (versionId == null) {
      return;
    }
    WorkflowVersion referencedVersion = requireById(versionId);
    if (!referencedVersion.getDefinitionId().equals(definitionId)) {
      throw new CommandConflictException(
          "WORKFLOW_VERSION_DEFINITION_MISMATCH", field + " must reference the same definition");
    }
  }
}
