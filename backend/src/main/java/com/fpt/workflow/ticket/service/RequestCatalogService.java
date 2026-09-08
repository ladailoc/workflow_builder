package com.fpt.workflow.ticket.service;

import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestCatalogService {

  private final RequestTypeRepository requestTypeRepository;
  private final WorkflowVersionRepository versionRepository;
  private final TicketFormValidationService formValidationService;

  public RequestCatalogService(
      RequestTypeRepository requestTypeRepository,
      WorkflowVersionRepository versionRepository,
      TicketFormValidationService formValidationService) {
    this.requestTypeRepository = requestTypeRepository;
    this.versionRepository = versionRepository;
    this.formValidationService = formValidationService;
  }

  @Transactional(readOnly = true)
  public List<CatalogItem> list() {
    return requestTypeRepository.findAllByActiveTrueOrderByCategoryAscNameAsc().stream()
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
