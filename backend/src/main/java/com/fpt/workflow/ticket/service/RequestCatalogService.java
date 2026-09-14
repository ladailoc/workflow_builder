package com.fpt.workflow.ticket.service;

import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestCatalogService {

  private final RequestTypeRepository requestTypeRepository;
  private final WorkflowVersionRepository versionRepository;
  private final TicketFormValidationService formValidationService;
  private final WorkflowDefinitionRepository definitionRepository;
  private final WorkflowFormRepository formRepository;

  public RequestCatalogService(
      RequestTypeRepository requestTypeRepository,
      WorkflowVersionRepository versionRepository,
      TicketFormValidationService formValidationService,
      WorkflowDefinitionRepository definitionRepository,
      WorkflowFormRepository formRepository) {
    this.requestTypeRepository = requestTypeRepository;
    this.versionRepository = versionRepository;
    this.formValidationService = formValidationService;
    this.definitionRepository = definitionRepository;
    this.formRepository = formRepository;
  }

  @Transactional(readOnly = true)
  public List<CatalogItem> list() {
    return requestTypeRepository.findAllByActiveTrueOrderByCategoryAscNameAsc().stream()
        .filter(this::isCreatable)
        .map(
            type ->
                new CatalogItem(
                    type.getId(),
                    type.getKey(),
                    type.getName(),
                    type.getDescription(),
                    type.getCategory()))
        .toList();
  }

  private boolean isCreatable(RequestType requestType) {
    return definitionRepository
        .findById(requestType.getWorkflowDefinitionId())
        .filter(definition -> definition.getLifecycle() == WorkflowDefinitionLifecycle.ACTIVE)
        .map(definition -> definition.getCurrentPublishedVersionId())
        .filter(java.util.Objects::nonNull)
        .map(
            versionId ->
                formRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(versionId).stream()
                    .anyMatch(form -> form.getFormType() == WorkflowFormType.TICKET_FORM))
        .orElse(false);
  }

  @Transactional(readOnly = true)
  public CreateSchema createSchema(String key) {
    RequestType requestType =
        requestTypeRepository
            .findByKey(key)
            .filter(RequestType::isActive)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "REQUEST_TYPE_NOT_FOUND", "Active request type was not found"));
    var contract = formValidationService.currentContract(requestType);
    WorkflowVersion version =
        versionRepository
            .findById(contract.workflowVersionId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "WORKFLOW_VERSION_NOT_FOUND", "Published workflow version was not found"));
    return new CreateSchema(
        requestType.getId(),
        requestType.getKey(),
        contract.workflowVersionId(),
        version.getVersionNo(),
        contract.schemaChecksum(),
        contract.schema());
  }

  public record CatalogItem(
      UUID id, String key, String name, String description, String category) {}

  public record CreateSchema(
      UUID requestTypeId,
      String requestTypeKey,
      UUID sourceWorkflowVersionId,
      int formSchemaVersion,
      String formSchemaChecksum,
      FormSchema ticketFormSchema) {}
}
