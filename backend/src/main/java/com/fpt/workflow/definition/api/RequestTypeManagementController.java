package com.fpt.workflow.definition.api;

import com.fpt.workflow.definition.dto.RequestTypeDtos;
import com.fpt.workflow.definition.dto.WorkflowManagementDtos;
import com.fpt.workflow.definition.service.DefinitionManagementCommandFacade;
import com.fpt.workflow.definition.service.WorkflowManagementQueryService;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.page.PageRequest;
import com.fpt.workflow.shared.domain.page.PageResult;
import com.fpt.workflow.shared.domain.page.SortOrder;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/request-types")
@PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
public class RequestTypeManagementController {

  private static final String COMMAND_ID = "X-Command-Id";
  private final WorkflowManagementQueryService queryService;
  private final DefinitionManagementCommandFacade commandFacade;

  public RequestTypeManagementController(
      WorkflowManagementQueryService queryService,
      DefinitionManagementCommandFacade commandFacade) {
    this.queryService = queryService;
    this.commandFacade = commandFacade;
  }

  @GetMapping
  public PageResult<WorkflowManagementDtos.RequestTypeAdminView> list(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Boolean active,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size) {
    return queryService.requestTypes(
        query, active, new PageRequest(page, size, List.of(SortOrder.ascending("name"))));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RequestTypeDtos.View create(
      @RequestHeader(COMMAND_ID) UUID commandId,
      @Valid @RequestBody RequestTypeDtos.Create request) {
    return commandFacade.createRequestType(new CommandId(commandId), request);
  }

  @GetMapping("/{requestTypeId}")
  public WorkflowManagementDtos.RequestTypeAdminView detail(@PathVariable UUID requestTypeId) {
    return queryService.requestType(requestTypeId);
  }

  @PutMapping("/{requestTypeId}")
  public RequestTypeDtos.View update(
      @PathVariable UUID requestTypeId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody WorkflowManagementDtos.UpdateRequestType request) {
    return commandFacade.updateRequestType(
        requestTypeId, new CommandId(commandId), new ExpectedVersion(expectedVersion), request);
  }

  @PostMapping("/{requestTypeId}/activate")
  public RequestTypeDtos.View activate(
      @PathVariable UUID requestTypeId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody(required = false) WorkflowManagementDtos.RequestTypeActivation request) {
    return setActive(requestTypeId, commandId, expectedVersion, request, true);
  }

  @PostMapping("/{requestTypeId}/deactivate")
  public RequestTypeDtos.View deactivate(
      @PathVariable UUID requestTypeId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody(required = false) WorkflowManagementDtos.RequestTypeActivation request) {
    return setActive(requestTypeId, commandId, expectedVersion, request, false);
  }

  private RequestTypeDtos.View setActive(
      UUID requestTypeId,
      UUID commandId,
      long expectedVersion,
      WorkflowManagementDtos.RequestTypeActivation request,
      boolean active) {
    return commandFacade.setRequestTypeActive(
        requestTypeId,
        active,
        new CommandId(commandId),
        new ExpectedVersion(expectedVersion),
        request == null ? null : request.reason());
  }
}
