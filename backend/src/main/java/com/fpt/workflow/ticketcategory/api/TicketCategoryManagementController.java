package com.fpt.workflow.ticketcategory.api;
import com.fpt.workflow.ticketcategory.domain.*;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.ticketcategory.dto.TicketCategoryBindingDtos;
import com.fpt.workflow.ticketcategory.service.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/ticket-categories") @PreAuthorize("isAuthenticated()")
public class TicketCategoryManagementController {
  private final TicketCategoryManagementService service;private final TicketCategoryBindingService bindings;private final TicketCategoryBindingCommandFacade bindingCommands;
  public TicketCategoryManagementController(TicketCategoryManagementService service,TicketCategoryBindingService bindings,TicketCategoryBindingCommandFacade bindingCommands){this.service=service;this.bindings=bindings;this.bindingCommands=bindingCommands;}
  @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')") public TicketCategory create(@RequestBody TicketCategoryManagementService.CreateCategory command){return service.create(command);}
  @PostMapping("/{id}/draft") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')") public TicketCategoryVersion draft(@PathVariable UUID id,@RequestBody TicketCategoryManagementService.DraftCommand command){return service.draft(id,command);}
  @PutMapping("/{id}/versions/{versionId}/binding") @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')") public TicketCategoryVersion binding(@PathVariable UUID id,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision,@RequestBody TicketCategoryManagementService.BindingCommand command){return service.binding(id,versionId,expectedRevision,command);}
  @PutMapping("/{id}/versions/{versionId}/mappings") @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')") public List<TicketCategoryMapping> mappings(@PathVariable UUID id,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision,@RequestBody List<TicketCategoryManagementService.MappingCommand> commands){return service.replaceMappings(id,versionId,expectedRevision,commands);}
  @PostMapping("/{id}/versions/{versionId}/validate") @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')") public List<CategoryIssue> validate(@PathVariable UUID id,@PathVariable UUID versionId){return service.validate(id,versionId);}
  @GetMapping("/{id}/versions/{versionId}/mappings") @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')") public List<TicketCategoryMapping> mappings(@PathVariable UUID id,@PathVariable UUID versionId){return service.mappings(id,versionId);}
  @PostMapping("/{id}/versions/{versionId}/publish") @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')") public TicketCategoryVersion publish(@PathVariable UUID id,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision){return service.publish(id,versionId,expectedRevision);}
  @GetMapping("/{id}/versions") @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')") public List<TicketCategoryVersion> versions(@PathVariable UUID id){return service.versions(id);}
  @GetMapping("/{id}/bindings") public TicketCategoryBindingService.BindingOverview bindings(@PathVariable UUID id){return bindings.describe(id);}
  @PutMapping("/{id}/bindings") public TicketCategoryBindingDtos.ScopeBindingView upsertTenantBinding(@PathVariable UUID id,@RequestHeader("X-Command-Id") UUID commandId,@RequestBody TicketCategoryBindingService.OverrideCommand command){return bindingCommands.upsert(id,new CommandId(commandId),command);}
  @DeleteMapping("/{id}/bindings/{tenantId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteTenantBinding(@PathVariable UUID id,@PathVariable UUID tenantId,@RequestHeader("X-Command-Id") UUID commandId){bindingCommands.delete(id,tenantId,new CommandId(commandId));}
}
