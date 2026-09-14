package com.fpt.workflow.definition.service;

import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.dto.WorkflowGraphDtos;
import com.fpt.workflow.definition.dto.WorkflowManagementDtos;
import com.fpt.workflow.definition.dto.WorkflowVersionDtos;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.dto.WorkflowFormDtos;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import com.fpt.workflow.shared.domain.page.PageRequest;
import com.fpt.workflow.shared.domain.page.PageResult;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Administrative read model for WorkflowDefinition and RequestType management. */
@Service
public class WorkflowManagementQueryService {

  private static final Map<String, String> WORKFLOW_SORTS =
      Map.of(
          "key", "key",
          "name", "name",
          "lifecycle", "lifecycle",
          "createdAt", "createdAt",
          "updatedAt", "updatedAt");
  private static final Map<String, String> REQUEST_TYPE_SORTS =
      Map.of(
          "key", "key",
          "name", "name",
          "category", "category",
          "createdAt", "createdAt",
          "updatedAt", "updatedAt");

  private final WorkflowDefinitionRepository definitionRepository;
  private final WorkflowVersionRepository versionRepository;
  private final NodeDefinitionRepository nodeRepository;
  private final EdgeDefinitionRepository edgeRepository;
  private final WorkflowFormRepository formRepository;
  private final RequestTypeRepository requestTypeRepository;

  public WorkflowManagementQueryService(
      WorkflowDefinitionRepository definitionRepository,
      WorkflowVersionRepository versionRepository,
      NodeDefinitionRepository nodeRepository,
      EdgeDefinitionRepository edgeRepository,
      WorkflowFormRepository formRepository,
      RequestTypeRepository requestTypeRepository) {
    this.definitionRepository = definitionRepository;
    this.versionRepository = versionRepository;
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.formRepository = formRepository;
    this.requestTypeRepository = requestTypeRepository;
  }

  @TransactionalQuery
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'OPERATOR', 'ADMIN')")
  public PageResult<WorkflowManagementDtos.Summary> workflows(
      String query, WorkflowDefinitionLifecycle lifecycle, PageRequest pageRequest) {
    String normalized = normalize(query);
    Pageable pageable = DefinitionPageables.toSpring(pageRequest, WORKFLOW_SORTS);
    Page<WorkflowDefinition> page;
    if (normalized != null && lifecycle != null) {
      String pattern = "%" + normalized.toLowerCase(Locale.ROOT) + "%";
      page = definitionRepository.searchByPatternAndLifecycle(pattern, lifecycle, pageable);
    } else if (normalized != null) {
      String pattern = "%" + normalized.toLowerCase(Locale.ROOT) + "%";
      page = definitionRepository.searchByPattern(pattern, pageable);
    } else if (lifecycle != null) {
      page = definitionRepository.findAllByLifecycle(lifecycle, pageable);
    } else {
      page = definitionRepository.findAll(pageable);
    }
    return new PageResult<>(
        page.getContent().stream().map(this::summary).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements());
  }

  @TransactionalQuery
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'OPERATOR', 'ADMIN')")
  public WorkflowManagementDtos.Detail workflow(UUID workflowId) {
    WorkflowDefinition definition = requireDefinition(workflowId);
    return new WorkflowManagementDtos.Detail(
        summary(definition),
        versionRepository.findAllByDefinitionIdOrderByVersionNoDesc(workflowId).stream()
            .map(WorkflowVersionDtos.View::from)
            .toList());
  }

  @TransactionalQuery
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'OPERATOR', 'ADMIN')")
  public WorkflowManagementDtos.VersionDetail version(UUID workflowId, UUID versionId) {
    requireDefinition(workflowId);
    WorkflowVersion version = requireVersion(versionId);
    requireSameDefinition(workflowId, version);
    return new WorkflowManagementDtos.VersionDetail(
        WorkflowVersionDtos.View.from(version),
        nodeRepository.findAllByWorkflowVersionIdOrderByNodeKeyAsc(versionId).stream()
            .map(WorkflowGraphDtos.NodeView::from)
            .toList(),
        edgeRepository.findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(versionId).stream()
            .map(WorkflowGraphDtos.EdgeView::from)
            .toList(),
        formRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(versionId).stream()
            .map(WorkflowFormDtos.View::from)
            .toList());
  }

  @TransactionalQuery
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public PageResult<WorkflowManagementDtos.RequestTypeAdminView> requestTypes(
      String query, Boolean active, PageRequest pageRequest) {
    String normalized = normalize(query);
    Pageable pageable = DefinitionPageables.toSpring(pageRequest, REQUEST_TYPE_SORTS);
    Page<RequestType> page;
    if (normalized != null && active != null) {
      String pattern = "%" + normalized.toLowerCase(Locale.ROOT) + "%";
      page = requestTypeRepository.searchByPatternAndActive(pattern, active, pageable);
    } else if (normalized != null) {
      String pattern = "%" + normalized.toLowerCase(Locale.ROOT) + "%";
      page = requestTypeRepository.searchByPattern(pattern, pageable);
    } else if (active != null) {
      page = requestTypeRepository.findAllByActive(active, pageable);
    } else {
      page = requestTypeRepository.findAll(pageable);
    }
    return new PageResult<>(
        page.getContent().stream().map(this::requestType).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements());
  }

  @TransactionalQuery
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowManagementDtos.RequestTypeAdminView requestType(UUID requestTypeId) {
    RequestType requestType =
        requestTypeRepository
            .findById(requestTypeId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "REQUEST_TYPE_NOT_FOUND", "Request type was not found"));
    return requestType(requestType);
  }

  private WorkflowManagementDtos.Summary summary(WorkflowDefinition definition) {
    WorkflowVersion published = nullableVersion(definition.getCurrentPublishedVersionId());
    WorkflowVersion draft = nullableVersion(definition.getActiveDraftVersionId());
    return new WorkflowManagementDtos.Summary(
        definition.getId(),
        definition.getKey(),
        definition.getName(),
        definition.getDescription(),
        definition.getLifecycle(),
        definition.getOwnerId(),
        definition.getCurrentPublishedVersionId(),
        published == null ? null : published.getVersionNo(),
        published == null ? null : published.getPublishedAt(),
        definition.getActiveDraftVersionId(),
        draft == null ? null : draft.getVersionNo(),
        draft == null ? null : draft.getRevision(),
        versionRepository.countByDefinitionId(definition.getId()),
        definition.getCreatedAt(),
        definition.getUpdatedAt(),
        definition.getLockVersion());
  }

  private WorkflowManagementDtos.RequestTypeAdminView requestType(RequestType requestType) {
    WorkflowDefinition definition = requireDefinition(requestType.getWorkflowDefinitionId());
    WorkflowVersion published = nullableVersion(definition.getCurrentPublishedVersionId());
    boolean schemaAvailable =
        published != null
            && formRepository
                .findAllByWorkflowVersionIdOrderByFormKeyAsc(published.getId())
                .stream()
                .anyMatch(form -> form.getFormType() == WorkflowFormType.TICKET_FORM);
    return new WorkflowManagementDtos.RequestTypeAdminView(
        requestType.getId(),
        requestType.getKey(),
        requestType.getName(),
        requestType.getDescription(),
        requestType.getCategory(),
        requestType.isActive(),
        requestType.getCreationPolicyJson(),
        definition.getId(),
        definition.getName(),
        definition.getLifecycle(),
        definition.getCurrentPublishedVersionId(),
        published == null ? null : published.getVersionNo(),
        schemaAvailable,
        requestType.getCreatedAt(),
        requestType.getUpdatedAt(),
        requestType.getLockVersion());
  }

  private WorkflowDefinition requireDefinition(UUID id) {
    return definitionRepository
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition was not found"));
  }

  private WorkflowVersion requireVersion(UUID id) {
    return versionRepository
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_VERSION_NOT_FOUND", "Workflow version was not found"));
  }

  private WorkflowVersion nullableVersion(UUID id) {
    return id == null ? null : requireVersion(id);
  }

  public void requireSameDefinition(UUID workflowId, WorkflowVersion version) {
    if (!version.getDefinitionId().equals(workflowId)) {
      throw new CommandConflictException(
          "WORKFLOW_VERSION_DEFINITION_MISMATCH",
          "Workflow version does not belong to the requested definition");
    }
  }

  private String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
