package com.fpt.workflow.definition.service;

import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.dto.WorkflowDefinitionDtos;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.domain.lifecycle.TransitionGuard;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import com.fpt.workflow.shared.domain.page.PageRequest;
import com.fpt.workflow.shared.domain.page.PageResult;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class WorkflowDefinitionService {

  private static final Map<String, String> SORT_PROPERTIES =
      Map.of(
          "key", "key",
          "name", "name",
          "lifecycle", "lifecycle",
          "createdAt", "createdAt",
          "updatedAt", "updatedAt");

  private final WorkflowDefinitionRepository workflowDefinitionRepository;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ActorContextProvider actorContextProvider;

  public WorkflowDefinitionService(
      WorkflowDefinitionRepository workflowDefinitionRepository,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ActorContextProvider actorContextProvider) {
    this.workflowDefinitionRepository = workflowDefinitionRepository;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.actorContextProvider = actorContextProvider;
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View create(WorkflowDefinitionDtos.Create request) {
    if (workflowDefinitionRepository.existsByKey(request.key())) {
      throw new CommandConflictException(
          "WORKFLOW_DEFINITION_KEY_CONFLICT", "Workflow definition key already exists");
    }
    WorkflowDefinition definition =
        WorkflowDefinition.create(
            uuidGenerator.generate(),
            request.key(),
            request.name(),
            request.description(),
            request.ownerId(),
            actorContextProvider.requireActor().actorId(),
            clock.now());
    return WorkflowDefinitionDtos.View.from(workflowDefinitionRepository.saveAndFlush(definition));
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View update(
      UUID id, ExpectedVersion expectedVersion, WorkflowDefinitionDtos.Update request) {
    WorkflowDefinition definition = requireById(id);
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(definition.getLockVersion()), expectedVersion);
    if (definition.getLifecycle() != request.lifecycle()) {
      TransitionGuard.requireAllowed(
          definition.getLifecycle(),
          request.lifecycle(),
          allowedLifecycleTargets(definition.getLifecycle()));
    }
    definition.updateDetails(
        request.name(), request.description(), request.ownerId(), request.lifecycle(), clock.now());
    return WorkflowDefinitionDtos.View.from(workflowDefinitionRepository.saveAndFlush(definition));
  }

  @TransactionalQuery
  public WorkflowDefinitionDtos.View get(UUID id) {
    return WorkflowDefinitionDtos.View.from(requireById(id));
  }

  @TransactionalQuery
  public WorkflowDefinitionDtos.View getByKey(String key) {
    return workflowDefinitionRepository
        .findByKey(key)
        .map(WorkflowDefinitionDtos.View::from)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition was not found"));
  }

  @TransactionalQuery
  public PageResult<WorkflowDefinitionDtos.View> list(PageRequest request) {
    var page =
        workflowDefinitionRepository.findAll(
            DefinitionPageables.toSpring(request, SORT_PROPERTIES));
    return new PageResult<>(
        page.getContent().stream().map(WorkflowDefinitionDtos.View::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements());
  }

  WorkflowDefinition requireById(UUID id) {
    return workflowDefinitionRepository
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition was not found"));
  }

  private static EnumSet<WorkflowDefinitionLifecycle> allowedLifecycleTargets(
      WorkflowDefinitionLifecycle current) {
    return switch (current) {
      case ACTIVE ->
          EnumSet.of(WorkflowDefinitionLifecycle.SUSPENDED, WorkflowDefinitionLifecycle.ARCHIVED);
      case SUSPENDED ->
          EnumSet.of(WorkflowDefinitionLifecycle.ACTIVE, WorkflowDefinitionLifecycle.ARCHIVED);
      case ARCHIVED -> EnumSet.noneOf(WorkflowDefinitionLifecycle.class);
    };
  }
}
