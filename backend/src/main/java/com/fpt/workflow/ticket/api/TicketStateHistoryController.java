package com.fpt.workflow.ticket.api;
import com.fpt.workflow.ticket.domain.TicketStateHistory;
import com.fpt.workflow.ticket.repository.TicketStateHistoryRepository;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.visibility.VisibilityResolver;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/tickets")
public class TicketStateHistoryController {
  private final TicketStateHistoryRepository histories;
  private final ActorContextProvider actors;
  private final VisibilityResolver visibility;
  public TicketStateHistoryController(TicketStateHistoryRepository histories,ActorContextProvider actors,VisibilityResolver visibility){this.histories=histories;this.actors=actors;this.visibility=visibility;}
  @GetMapping("/{ticketId}/state-history") @PreAuthorize("isAuthenticated()")
  public List<TicketStateHistory> history(@PathVariable UUID ticketId){visibility.requireTicketVisible(actors.requireActor(),ticketId);return histories.findAllByTicketIdOrderByEnteredAtAsc(ticketId);}
}
