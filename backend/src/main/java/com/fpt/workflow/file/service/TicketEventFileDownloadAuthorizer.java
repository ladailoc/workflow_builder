package com.fpt.workflow.file.service;

import com.fpt.workflow.file.domain.*;
import com.fpt.workflow.rework.repository.RevisionRequestRepository;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.*;
import com.fpt.workflow.security.visibility.VisibilityResolver;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.ticket.repository.TicketRepository;
import com.fpt.workflow.ticket.repository.TicketRevisionRepository;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public final class TicketEventFileDownloadAuthorizer implements FileDownloadAuthorizer {
  private static final PermissionKey SENSITIVE_READ = PermissionKey.of("FILE.SENSITIVE.READ");
  private final TicketRepository tickets;
  private final TicketRevisionRepository revisions;
  private final EventRepository events;
  private final NodeExecutionRepository nodes;
  private final TaskExecutionRepository tasks;
  private final RevisionRequestRepository revisionRequests;
  private final VisibilityResolver visibilityResolver;

  public TicketEventFileDownloadAuthorizer(
      TicketRepository tickets,
      TicketRevisionRepository revisions,
      EventRepository events,
      NodeExecutionRepository nodes,
      TaskExecutionRepository tasks,
      RevisionRequestRepository revisionRequests,
      VisibilityResolver visibilityResolver) {
    this.tickets = tickets;
    this.revisions = revisions;
    this.events = events;
    this.nodes = nodes;
    this.tasks = tasks;
    this.revisionRequests = revisionRequests;
    this.visibilityResolver = visibilityResolver;
  }

  @Override
  public boolean mayDownload(ActorContext actor, StoredFile file, List<FileLink> links) {
    if (file.isSensitive()
        && !actor.hasPermission(SENSITIVE_READ)
        && !actor.hasRole(RoleKey.OPERATOR)
        && !actor.hasRole(RoleKey.ADMIN)) return false;
    if (actor.hasRole(RoleKey.OPERATOR) || actor.hasRole(RoleKey.ADMIN)) return true;
    if (file.getUploadedBy() != null && file.getUploadedBy().equals(actor.actorId())) {
      return true;
    }
    return links.stream()
        .map(this::ticketId)
        .flatMap(Optional::stream)
        .map(tickets::findById)
        .flatMap(Optional::stream)
        .anyMatch(ticket -> visibilityResolver.mayViewTicket(actor, ticket.getId()));
  }

  private Optional<UUID> ticketId(FileLink link) {
    return switch (link.getOwnerType()) {
      case TICKET_REVISION -> revisions.findById(link.getOwnerId()).map(r -> r.getTicketId());
      case EVENT -> events.findById(link.getOwnerId()).map(e -> e.getTicketId());
      case NODE_EXECUTION ->
          nodes
              .findById(link.getOwnerId())
              .flatMap(n -> events.findById(n.getEventId()))
              .map(e -> e.getTicketId());
      case TASK_EXECUTION ->
          tasks
              .findById(link.getOwnerId())
              .flatMap(t -> nodes.findById(t.getNodeExecutionId()))
              .flatMap(n -> events.findById(n.getEventId()))
              .map(e -> e.getTicketId());
      case REVISION_REQUEST ->
          revisionRequests
              .findById(link.getOwnerId())
              .flatMap(r -> events.findById(r.getEventId()))
              .map(e -> e.getTicketId());
    };
  }
}
