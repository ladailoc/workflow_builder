package com.fpt.workflow.definition.api;

import com.fpt.workflow.definition.dependency.DependencyReport;
import com.fpt.workflow.definition.dependency.FieldChange;
import com.fpt.workflow.definition.dependency.FieldChangeKind;
import com.fpt.workflow.definition.dependency.WorkflowFieldDependencyService;
import com.fpt.workflow.definition.dto.WorkflowDefinitionDtos;
import com.fpt.workflow.definition.dto.WorkflowGraphDtos;
import com.fpt.workflow.definition.dto.WorkflowManagementDtos;
import com.fpt.workflow.definition.dto.WorkflowVersionDtos;
import com.fpt.workflow.definition.governance.WorkflowRollbackService;
import com.fpt.workflow.definition.governance.WorkflowSemanticDiffService;
import com.fpt.workflow.definition.publish.WorkflowPublishService;
import com.fpt.workflow.definition.service.DefinitionManagementCommandFacade;
import com.fpt.workflow.definition.service.WorkflowConfigSchemaUpgradeService;
import com.fpt.workflow.definition.service.WorkflowFormService;
import com.fpt.workflow.definition.service.WorkflowManagementQueryService;
import com.fpt.workflow.definition.service.WorkflowVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.service.WorkflowSimulationService;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import com.fpt.workflow.shared.domain.page.PageRequest;
import com.fpt.workflow.shared.domain.page.PageResult;
import com.fpt.workflow.shared.domain.page.SortOrder;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
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
@RequestMapping("/api/v1/workflows")
@PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'OPERATOR', 'ADMIN')")
public class WorkflowManagementController {

  private static final String COMMAND_ID = "X-Command-Id";
  private final WorkflowManagementQueryService queryService;
  private final WorkflowVersionService versionService;
  private final WorkflowValidationService validationService;
  private final WorkflowSemanticDiffService diffService;
  private final DefinitionManagementCommandFacade commandFacade;
  private final WorkflowFieldDependencyService fieldDependencyService;
  private final com.fpt.workflow.definition.service.WorkflowConfigSchemaUpgradeService upgradeService;
  private final WorkflowSimulationService simulationService;

  public WorkflowManagementController(
      WorkflowManagementQueryService queryService,
      WorkflowVersionService versionService,
      WorkflowValidationService validationService,
      WorkflowSemanticDiffService diffService,
      DefinitionManagementCommandFacade commandFacade,
      WorkflowFieldDependencyService fieldDependencyService) {
    this(queryService, versionService, validationService, diffService, commandFacade, fieldDependencyService, null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public WorkflowManagementController(
      WorkflowManagementQueryService queryService,
      WorkflowVersionService versionService,
      WorkflowValidationService validationService,
      WorkflowSemanticDiffService diffService,
      DefinitionManagementCommandFacade commandFacade,
      WorkflowFieldDependencyService fieldDependencyService,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.definition.service.WorkflowConfigSchemaUpgradeService upgradeService,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          WorkflowSimulationService simulationService) {
    this.queryService = queryService;
    this.versionService = versionService;
    this.validationService = validationService;
    this.diffService = diffService;
    this.commandFacade = commandFacade;
    this.fieldDependencyService = fieldDependencyService;
    this.upgradeService = upgradeService;
    this.simulationService = simulationService;
  }

  @GetMapping
  public PageResult<WorkflowManagementDtos.Summary> list(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) WorkflowDefinitionLifecycle lifecycle,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return queryService.workflows(
        query, lifecycle, new PageRequest(page, size, List.of(SortOrder.descending("updatedAt"))));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View create(
      @RequestHeader(COMMAND_ID) UUID commandId,
      @Valid @RequestBody WorkflowDefinitionDtos.Create request) {
    return commandFacade.createWorkflow(new CommandId(commandId), request);
  }

  @GetMapping("/{workflowId}")
  public WorkflowManagementDtos.Detail detail(@PathVariable UUID workflowId) {
    return queryService.workflow(workflowId);
  }

  @PutMapping("/{workflowId}")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View update(
      @PathVariable UUID workflowId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody WorkflowManagementDtos.UpdateWorkflow request) {
    return commandFacade.updateWorkflow(
        workflowId, new CommandId(commandId), new ExpectedVersion(expectedVersion), request);
  }

  @PostMapping("/{workflowId}/draft")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public WorkflowVersionDtos.View createDraft(
      @PathVariable UUID workflowId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestBody(required = false) CreateDraftCommand request) {
    CreateDraftCommand command = request == null ? new CreateDraftCommand(null, null) : request;
    return commandFacade.createDraft(
        workflowId,
        new CommandId(commandId),
        command.basedOnVersionId(),
        command.rollbackOfVersionId());
  }

  @GetMapping("/{workflowId}/versions")
  public PageResult<WorkflowVersionDtos.View> versions(
      @PathVariable UUID workflowId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size) {
    return versionService.listByDefinition(
        workflowId, new PageRequest(page, size, List.of(SortOrder.descending("versionNo"))));
  }

  @GetMapping("/{workflowId}/versions/{versionId}")
  public WorkflowManagementDtos.VersionDetail version(
      @PathVariable UUID workflowId, @PathVariable UUID versionId) {
    return queryService.version(workflowId, versionId);
  }

  @GetMapping("/{workflowId}/versions/{versionId}/graph")
  public WorkflowManagementDtos.VersionDetail graph(
      @PathVariable UUID workflowId, @PathVariable UUID versionId) {
    return queryService.version(workflowId, versionId);
  }

  @PutMapping("/{workflowId}/versions/{versionId}/graph")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public WorkflowGraphDtos.GraphView saveGraph(
      @PathVariable UUID workflowId,
      @PathVariable UUID versionId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody WorkflowManagementDtos.SaveGraph request) {
    return commandFacade.saveGraph(
        workflowId,
        versionId,
        new CommandId(commandId),
        new ExpectedVersion(expectedVersion),
        request);
  }

  @PutMapping("/{workflowId}/versions/{versionId}/ticket-form")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public WorkflowFormService.FormMutation saveTicketForm(
      @PathVariable UUID workflowId,
      @PathVariable UUID versionId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody WorkflowManagementDtos.SaveTicketForm request) {
    return commandFacade.saveTicketForm(
        workflowId,
        versionId,
        new CommandId(commandId),
        new ExpectedVersion(expectedVersion),
        request);
  }

  @PostMapping("/{workflowId}/versions/{versionId}/validate")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public WorkflowManagementDtos.ValidationView validate(
      @PathVariable UUID workflowId, @PathVariable UUID versionId) {
    queryService.version(workflowId, versionId);
    return WorkflowManagementDtos.ValidationView.from(
        validationService.validate(versionId).compilation());
  }

  @GetMapping("/{workflowId}/versions/{versionId}/validation")
  public WorkflowManagementDtos.ValidationView getValidation(
      @PathVariable UUID workflowId, @PathVariable UUID versionId) {
    queryService.version(workflowId, versionId);
    return validationService.getLatestValidation(versionId);
  }

  @GetMapping("/{workflowId}/versions/{versionId}/field-dependencies")
  public DependencyReport getFieldDependencies(
      @PathVariable UUID workflowId,
      @PathVariable UUID versionId,
      @RequestParam String fieldKey,
      @RequestParam(defaultValue = "DELETE") FieldChangeKind kind,
      @RequestParam(required = false) String replacementKey,
      @RequestParam(required = false) CanonicalValueType replacementType) {
    queryService.version(workflowId, versionId);
    FieldChange change =
        switch (kind) {
          case RENAME -> FieldChange.rename(versionId, fieldKey, replacementKey);
          case DELETE -> FieldChange.delete(versionId, fieldKey);
          case TYPE_CHANGE ->
              FieldChange.typeChange(
                  versionId,
                  fieldKey,
                  replacementType == null ? null : TypeDescriptor.required(replacementType));
        };
    return fieldDependencyService.analyze(change);
  }

  @PostMapping("/{workflowId}/versions/{versionId}/publish")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowPublishService.PublishResult publish(
      @PathVariable UUID workflowId,
      @PathVariable UUID versionId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody WorkflowManagementDtos.PublishCommand request) {
    return commandFacade.publish(
        workflowId,
        versionId,
        new CommandId(commandId),
        new ExpectedVersion(expectedVersion),
        request.expectedRevision(),
        request.acknowledgedWarnings());
  }

  /** P2-08: dry-run simulation on a draft definition (§22.8): no Event or side effects. */
  @PostMapping("/{workflowId}/versions/{versionId}/simulate")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public com.fpt.workflow.definition.service.WorkflowSimulationService.SimulationResult simulate(
      @PathVariable UUID workflowId,
      @PathVariable UUID versionId,
      @RequestBody(required = false) JsonNode request) {
    queryService.version(workflowId, versionId);
    if (simulationService == null) {
      throw new CommandConflictException(
          "WORKFLOW_SIMULATION_SERVICE_UNAVAILABLE",
          "WorkflowSimulationService is not registered");
    }
    return simulationService.simulate(versionId, parseSimulationSample(request));
  }

  private com.fpt.workflow.definition.service.WorkflowSimulationService.SampleContext
      parseSimulationSample(JsonNode request) {
    if (request == null) {
      return new com.fpt.workflow.definition.service.WorkflowSimulationService.SampleContext(
          com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
          com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode());
    }
    JsonNode ticketData = request.path("ticketData");
    if (!ticketData.isObject()) {
      ticketData = request.path("sample").path("ticketData");
      if (!ticketData.isObject()) ticketData = request;
    }
    JsonNode subjects = request.path("subjects");
    if (!subjects.isArray()) subjects = request.path("sample").path("subjects");
    if (!subjects.isArray()) {
      subjects = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }
    return new com.fpt.workflow.definition.service.WorkflowSimulationService.SampleContext(
        ticketData, subjects);
  }

  /** P2-07: explicitly upgrades Draft node configs to the current node config schema version. */
  @PostMapping("/{workflowId}/versions/{versionId}/upgrade-config-schema")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public WorkflowConfigSchemaUpgradeService.UpgradeResult upgradeConfigSchema(
      @PathVariable UUID workflowId,
      @PathVariable UUID versionId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody WorkflowManagementDtos.PublishCommand request) {
    queryService.version(workflowId, versionId);
    if (upgradeService == null) {
      throw new CommandConflictException(
          "WORKFLOW_CONFIG_UPGRADE_UNAVAILABLE",
          "Config schema upgrade service is not registered");
    }
    return upgradeService.upgradeDraftToCurrentSchema(
        versionId, new ExpectedVersion(expectedVersion), request.expectedRevision());
  }

  @GetMapping("/{workflowId}/versions/{fromVersionId}/diff/{toVersionId}")
  public WorkflowSemanticDiffService.SemanticDiff diff(
      @PathVariable UUID workflowId,
      @PathVariable UUID fromVersionId,
      @PathVariable UUID toVersionId) {
    queryService.version(workflowId, fromVersionId);
    queryService.version(workflowId, toVersionId);
    return diffService.diff(fromVersionId, toVersionId);
  }

  @PostMapping("/{workflowId}/versions/{sourceVersionId}/clone-as-draft")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowRollbackService.CloneDraftResult cloneAsDraft(
      @PathVariable UUID workflowId,
      @PathVariable UUID sourceVersionId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedDefinitionVersion) {
    return commandFacade.cloneAsDraft(
        workflowId,
        sourceVersionId,
        new CommandId(commandId),
        new ExpectedVersion(expectedDefinitionVersion));
  }

  @PostMapping("/{workflowId}/suspend")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View suspend(
      @PathVariable UUID workflowId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody(required = false) WorkflowManagementDtos.LifecycleCommand request) {
    return lifecycle(workflowId, commandId, expectedVersion, request, "SUSPEND");
  }

  @PostMapping("/{workflowId}/reactivate")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View reactivate(
      @PathVariable UUID workflowId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody(required = false) WorkflowManagementDtos.LifecycleCommand request) {
    return lifecycle(workflowId, commandId, expectedVersion, request, "REACTIVATE");
  }

  @PostMapping("/{workflowId}/archive")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowDefinitionDtos.View archive(
      @PathVariable UUID workflowId,
      @RequestHeader(COMMAND_ID) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody(required = false) WorkflowManagementDtos.LifecycleCommand request) {
    return lifecycle(workflowId, commandId, expectedVersion, request, "ARCHIVE");
  }

  private WorkflowDefinitionDtos.View lifecycle(
      UUID workflowId,
      UUID commandId,
      long expectedVersion,
      WorkflowManagementDtos.LifecycleCommand request,
      String action) {
    return commandFacade.lifecycle(
        workflowId,
        action,
        new CommandId(commandId),
        new ExpectedVersion(expectedVersion),
        request == null ? null : request.reason());
  }

  public record CreateDraftCommand(UUID basedOnVersionId, UUID rollbackOfVersionId) {}
}
