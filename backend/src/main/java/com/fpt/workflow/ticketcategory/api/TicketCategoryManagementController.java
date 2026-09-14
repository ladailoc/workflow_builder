package com.fpt.workflow.ticketcategory.api;
import com.fpt.workflow.ticketcategory.domain.*;
import com.fpt.workflow.ticketcategory.service.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/ticket-categories") @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')")
public class TicketCategoryManagementController {
  private final TicketCategoryManagementService service;public TicketCategoryManagementController(TicketCategoryManagementService service){this.service=service;}
  @PostMapping @ResponseStatus(HttpStatus.CREATED) public TicketCategory create(@RequestBody TicketCategoryManagementService.CreateCategory command){return service.create(command);}
  @PostMapping("/{id}/draft") @ResponseStatus(HttpStatus.CREATED) public TicketCategoryVersion draft(@PathVariable UUID id,@RequestBody TicketCategoryManagementService.DraftCommand command){return service.draft(id,command);}
  @PutMapping("/{id}/versions/{versionId}/binding") public TicketCategoryVersion binding(@PathVariable UUID id,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision,@RequestBody TicketCategoryManagementService.BindingCommand command){return service.binding(id,versionId,expectedRevision,command);}
  @PutMapping("/{id}/versions/{versionId}/mappings") public List<TicketCategoryMapping> mappings(@PathVariable UUID id,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision,@RequestBody List<TicketCategoryManagementService.MappingCommand> commands){return service.replaceMappings(id,versionId,expectedRevision,commands);}
  @PostMapping("/{id}/versions/{versionId}/validate") public List<CategoryIssue> validate(@PathVariable UUID id,@PathVariable UUID versionId){return service.validate(id,versionId);}
  @PostMapping("/{id}/versions/{versionId}/publish") public TicketCategoryVersion publish(@PathVariable UUID id,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision){return service.publish(id,versionId,expectedRevision);}
  @GetMapping("/{id}/versions") public List<TicketCategoryVersion> versions(@PathVariable UUID id){return service.versions(id);}
}
