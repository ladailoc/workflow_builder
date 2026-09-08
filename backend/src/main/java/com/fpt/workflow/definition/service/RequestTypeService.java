package com.fpt.workflow.definition.service;

import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.dto.RequestTypeDtos;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
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
public class RequestTypeService {

  private static final Map<String, String> SORT_PROPERTIES =
      Map.of(
          "key", "key",
          "name", "name",
          "category", "category",
          "createdAt", "createdAt",
          "updatedAt", "updatedAt");

  private final RequestTypeRepository requestTypeRepository;
  private final WorkflowDefinitionRepository workflowDefinitionRepository;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;

  public RequestTypeService(
      RequestTypeRepository requestTypeRepository,
      WorkflowDefinitionRepository workflowDefinitionRepository,
      UuidGenerator uuidGenerator,
      PlatformClock clock) {
    this.requestTypeRepository = requestTypeRepository;
    this.workflowDefinitionRepository = workflowDefinitionRepository;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public RequestTypeDtos.View create(RequestTypeDtos.Create request) {
    if (requestTypeRepository.existsByKey(request.key())) {
      throw new CommandConflictException(
          "REQUEST_TYPE_KEY_CONFLICT", "Request type key already exists");
    }
    requireEligibleDefinition(request.workflowDefinitionId(), request.active());
    RequestType requestType =
        RequestType.create(
            uuidGenerator.generate(),
            request.key(),
            request.name(),
            request.description(),
            request.category(),
            request.workflowDefinitionId(),
            request.active(),
            request.creationPolicyJson(),
            clock.now());
    return RequestTypeDtos.View.from(requestTypeRepository.saveAndFlush(requestType));
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public RequestTypeDtos.View update(
      UUID id, ExpectedVersion expectedVersion, RequestTypeDtos.Update request) {
    RequestType requestType = requireById(id);
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(requestType.getLockVersion()), expectedVersion);
    requireEligibleDefinition(request.workflowDefinitionId(), request.active());
    requestType.update(
        request.name(),
        request.description(),
        request.category(),
        request.workflowDefinitionId(),
        request.active(),
        request.creationPolicyJson(),
        clock.now());
    return RequestTypeDtos.View.from(requestTypeRepository.saveAndFlush(requestType));
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public void delete(UUID id, ExpectedVersion expectedVersion) {
    RequestType requestType = requireById(id);
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(requestType.getLockVersion()), expectedVersion);
    requestTypeRepository.delete(requestType);
    requestTypeRepository.flush();
  }

  @TransactionalQuery
  public RequestTypeDtos.View get(UUID id) {
    return RequestTypeDtos.View.from(requireById(id));
  }

  @TransactionalQuery
  public RequestTypeDtos.View getByKey(String key) {
    return requestTypeRepository
        .findByKey(key)
        .map(RequestTypeDtos.View::from)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "REQUEST_TYPE_NOT_FOUND", "Request type was not found"));
  }

  @TransactionalQuery
  public PageResult<RequestTypeDtos.View> list(PageRequest request) {
    var page =
        requestTypeRepository.findAll(DefinitionPageables.toSpring(request, SORT_PROPERTIES));
    return new PageResult<>(
        page.getContent().stream().map(RequestTypeDtos.View::from).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements());
  }

  private RequestType requireById(UUID id) {
    return requestTypeRepository
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "REQUEST_TYPE_NOT_FOUND", "Request type was not found"));
  }

  private void requireEligibleDefinition(UUID definitionId, boolean activeRequestType) {
    WorkflowDefinition definition =
        workflowDefinitionRepository
            .findById(definitionId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition was not found"));
    if (activeRequestType && definition.getLifecycle() != WorkflowDefinitionLifecycle.ACTIVE) {
      throw new CommandConflictException(
          "WORKFLOW_DEFINITION_NOT_ACTIVE",
          "An active request type must reference an active workflow definition");
    }
  }
}
