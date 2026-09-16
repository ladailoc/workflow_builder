package com.fpt.workflow.ticketcategory.api;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.ticketcategory.dto.TicketCategoryDtos;
import com.fpt.workflow.ticketcategory.service.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/ticket-categories")
@PreAuthorize("isAuthenticated()")
public class TicketCategoryController {
  private final TicketCategoryService categories;private final TicketCategoryCreateCommandFacade creates;
  public TicketCategoryController(TicketCategoryService categories,TicketCategoryCreateCommandFacade creates){this.categories=categories;this.creates=creates;}
  @GetMapping public List<TicketCategoryService.CatalogItem> list(@RequestParam(defaultValue="true") boolean active){return categories.listCreatable();}
  @GetMapping("/{categoryKey}/create-contract") public TicketCategoryService.CreateContract contract(@PathVariable String categoryKey,@RequestParam(required=false) UUID tenantId){return categories.resolvePublishedForCreate(categoryKey,tenantId);}
  @PostMapping("/{categoryKey}/tickets") @ResponseStatus(HttpStatus.CREATED)
  public TicketCategoryDtos.CreatedTicket create(@PathVariable String categoryKey,@RequestHeader("X-Command-Id") UUID commandId,@RequestBody TicketCategoryDtos.CreateTicket request){return creates.create(categoryKey,new CommandId(commandId),request);}
}
