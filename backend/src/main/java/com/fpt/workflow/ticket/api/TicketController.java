package com.fpt.workflow.ticket.api;

import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.ticket.dto.TicketDtos;
import com.fpt.workflow.ticket.service.TicketCommandFacade;
import com.fpt.workflow.ticket.service.TicketService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

  public static final String COMMAND_ID_HEADER = "X-Command-Id";

  private final TicketCommandFacade commandFacade;
  private final TicketService ticketService;

  public TicketController(TicketCommandFacade commandFacade, TicketService ticketService) {
    this.commandFacade = commandFacade;
    this.ticketService = ticketService;
  }

  @PostMapping("/drafts")
  @ResponseStatus(HttpStatus.CREATED)
  public TicketDtos.AggregateView createDraft(
      @RequestHeader(COMMAND_ID_HEADER) UUID commandId,
      @RequestBody TicketDtos.CreateDraft request) {
    return commandFacade.createDraft(new CommandId(commandId), request);
  }

  @PutMapping("/{id}/draft")
  public TicketDtos.AggregateView updateDraft(
      @PathVariable UUID id,
      @RequestHeader(COMMAND_ID_HEADER) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody TicketDtos.UpdateDraft request) {
    return commandFacade.updateDraft(
        id, new CommandId(commandId), new ExpectedVersion(expectedVersion), request);
  }

  @PostMapping("/{id}/submit")
  public TicketDtos.AggregateView submit(
      @PathVariable UUID id,
      @RequestHeader(COMMAND_ID_HEADER) UUID commandId,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody TicketDtos.Submit request) {
    return commandFacade.submit(
        id, new CommandId(commandId), new ExpectedVersion(expectedVersion), request);
  }

  @GetMapping("/{id}")
  public TicketDtos.AggregateView get(@PathVariable UUID id) {
    return ticketService.get(id);
  }
}
