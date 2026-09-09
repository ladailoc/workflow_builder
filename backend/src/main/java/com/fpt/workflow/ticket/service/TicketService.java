package com.fpt.workflow.ticket.service;

import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.domain.TicketRevision;
import com.fpt.workflow.ticket.domain.TicketSubject;
import com.fpt.workflow.ticket.dto.TicketDtos;
import com.fpt.workflow.ticket.repository.TicketRepository;
import com.fpt.workflow.ticket.repository.TicketRevisionRepository;
import com.fpt.workflow.ticket.repository.TicketSubjectRepository;
import com.fpt.workflow.ticket.service.TicketFormValidationService.TicketFormContract;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class TicketService {

  private final TicketRepository ticketRepository;
  private final TicketRevisionRepository revisionRepository;
  private final TicketSubjectRepository subjectRepository;
  private final RequestTypeRepository requestTypeRepository;
  private final TicketFormValidationService ticketFormValidationService;
  private final ActorContextProvider actorContextProvider;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final EventRepository eventRepository;

  public TicketService(
      TicketRepository ticketRepository,
      TicketRevisionRepository revisionRepository,
      TicketSubjectRepository subjectRepository,
      RequestTypeRepository requestTypeRepository,
      TicketFormValidationService ticketFormValidationService,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      EventRepository eventRepository) {
    this.ticketRepository = ticketRepository;
    this.revisionRepository = revisionRepository;
    this.subjectRepository = subjectRepository;
    this.requestTypeRepository = requestTypeRepository;
    this.ticketFormValidationService = ticketFormValidationService;
    this.actorContextProvider = actorContextProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.eventRepository = eventRepository;
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView createDraft(TicketDtos.CreateDraft request) {
    Objects.requireNonNull(request, "request");
    ActorContext actor = actorContextProvider.requireActor();
    RequestType requestType = requireActiveRequestType(request.requestTypeId());
    ticketFormValidationService.validateDraft(requestType, request.dataJson(), actor);
    Instant now = clock.now();
    Ticket ticket =
        Ticket.createDraft(
            uuidGenerator.generate(),
            request.requestTypeId(),
            actor.actorId(),
            request.dataJson(),
            now);
    ticketRepository.saveAndFlush(ticket);
    replaceSubjects(ticket.getId(), request.subjects(), now);
    return aggregate(ticket);
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView updateDraft(
      UUID ticketId, ExpectedVersion expectedVersion, TicketDtos.UpdateDraft request) {
    Objects.requireNonNull(request, "request");
    Ticket ticket = requireOwnedTicket(ticketId);
    requireExpectedVersion(ticket, expectedVersion);
    requireDataRevision(ticket, request.expectedDataRevision());
    ActorContext actor = actorContextProvider.requireActor();
    RequestType requestType = requireActiveRequestType(ticket.getRequestTypeId());
    ticketFormValidationService.validateDraft(requestType, request.dataJson(), actor);
    Instant now = clock.now();
    ticket.updateDraft(request.dataJson(), now);
    replaceSubjects(ticketId, request.subjects(), now);
    return aggregate(ticketRepository.saveAndFlush(ticket));
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView submit(
      UUID ticketId, ExpectedVersion expectedVersion, TicketDtos.Submit request) {
    return submit(ticketId, expectedVersion, request, new CommandId(uuidGenerator.generate()));
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView submit(
      UUID ticketId,
      ExpectedVersion expectedVersion,
      TicketDtos.Submit request,
      CommandId commandId) {
    Objects.requireNonNull(request, "request");
    Ticket ticket = requireOwnedTicket(ticketId);
    requireExpectedVersion(ticket, expectedVersion);
    requireDataRevision(ticket, request.expectedDataRevision());
    ActorContext actor = actorContextProvider.requireActor();
    RequestType requestType = requireActiveRequestType(ticket.getRequestTypeId());
    TicketFormContract formContract =
        ticketFormValidationService.validateSubmission(
            requestType,
            ticket.getDataJson(),
            actor,
            request.sourceWorkflowVersionId(),
            request.schemaChecksum());
    Instant now = clock.now();
    long revisionNo = ticket.nextRevisionNo();
    TicketRevision revision =
        TicketRevision.create(
            uuidGenerator.generate(),
            ticketId,
            revisionNo,
            ticket.getDataJson(),
            formContract.workflowVersionId().toString(),
            formContract.schemaChecksum(),
            actor.actorId(),
            now,
            request.changeReason());
    ticket.submit(revision.getId(), revisionNo, revision.getDataSnapshotJson(), now);
    ticketRepository.saveAndFlush(ticket);
    revisionRepository.saveAndFlush(revision);
    eventRepository.saveAndFlush(
        Event.createRoot(
            uuidGenerator.generate(),
            ticket.getId(),
            formContract.workflowVersionId(),
            revision.getId(),
            null,
            null,
            "TICKET_SUBMIT",
            commandId.toString(),
            objectNode(),
            actor.actorId(),
            now));
    return aggregate(ticket);
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView recordBusinessRevision(
      UUID ticketId, ExpectedVersion expectedVersion, TicketDtos.RecordRevision request) {
    Objects.requireNonNull(request, "request");
    Ticket ticket = requireOwnedTicket(ticketId);
    requireExpectedVersion(ticket, expectedVersion);
    ActorContext actor = actorContextProvider.requireActor();
    RequestType requestType = requireActiveRequestType(ticket.getRequestTypeId());
    TicketFormContract formContract =
        ticketFormValidationService.validateSubmission(
            requestType,
            request.dataJson(),
            actor,
            request.sourceWorkflowVersionId(),
            request.schemaChecksum());
    Instant now = clock.now();
    long revisionNo = ticket.nextRevisionNo();
    TicketRevision revision =
        TicketRevision.create(
            uuidGenerator.generate(),
            ticketId,
            revisionNo,
            request.dataJson(),
            formContract.workflowVersionId().toString(),
            formContract.schemaChecksum(),
            actor.actorId(),
            now,
            requireRevisionReason(request.changeReason()));
    ticket.recordBusinessRevision(
        revision.getId(), revisionNo, revision.getDataSnapshotJson(), now);
    ticketRepository.saveAndFlush(ticket);
    revisionRepository.saveAndFlush(revision);
    replaceSubjects(ticketId, request.subjects(), now);
    return aggregate(ticket);
  }

  @TransactionalQuery
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView get(UUID ticketId) {
    return aggregate(requireOwnedTicket(ticketId));
  }

  @TransactionalQuery
  @PreAuthorize("isAuthenticated()")
  public List<TicketDtos.TicketView> listMyTickets() {
    ActorContext actor = actorContextProvider.requireActor();
    List<Ticket> tickets;
    if (actor.hasRole(RoleKey.ADMIN) || actor.hasRole(RoleKey.OPERATOR)) {
      tickets = ticketRepository.findAllByOrderByCreatedAtDesc();
    } else {
      tickets = ticketRepository.findAllByCreatorIdOrderByCreatedAtDesc(actor.actorId());
    }
    return tickets.stream().map(TicketDtos.TicketView::from).toList();
  }

  private Ticket requireOwnedTicket(UUID ticketId) {
    Ticket ticket =
        ticketRepository
            .findById(Objects.requireNonNull(ticketId, "ticketId"))
            .orElseThrow(
                () -> new ResourceNotFoundException("TICKET_NOT_FOUND", "Ticket was not found"));
    ActorContext actor = actorContextProvider.requireActor();
    if (!ticket.getCreatorId().equals(actor.actorId()) && !actor.hasRole(RoleKey.ADMIN)) {
      throw new AccessDeniedException("Ticket is not owned by the authenticated actor");
    }
    return ticket;
  }

  private RequestType requireActiveRequestType(UUID requestTypeId) {
    RequestType requestType =
        requestTypeRepository
            .findById(Objects.requireNonNull(requestTypeId, "requestTypeId"))
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "REQUEST_TYPE_NOT_FOUND", "Request type was not found"));
    if (!requestType.isActive()) {
      throw new CommandConflictException(
          "REQUEST_TYPE_INACTIVE", "Tickets cannot be created for an inactive request type");
    }
    return requestType;
  }

  private void requireExpectedVersion(Ticket ticket, ExpectedVersion expectedVersion) {
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(ticket.getLockVersion()), expectedVersion);
  }

  private void requireDataRevision(Ticket ticket, long expectedDataRevision) {
    if (expectedDataRevision < 0 || ticket.getDataRevision() != expectedDataRevision) {
      throw new CommandConflictException(
          "TICKET_DRAFT_REVISION_CONFLICT", "Ticket data revision changed before the command");
    }
  }

  private com.fasterxml.jackson.databind.node.ObjectNode objectNode() {
    return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
  }

  private String requireRevisionReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new CommandConflictException(
          "TICKET_REVISION_REASON_REQUIRED", "A business-data revision requires a reason");
    }
    return reason;
  }

  private void replaceSubjects(
      UUID ticketId, List<TicketDtos.SubjectInput> requestedSubjects, Instant now) {
    List<TicketDtos.SubjectInput> subjects =
        requestedSubjects == null ? List.of() : List.copyOf(requestedSubjects);
    List<TicketSubject> replacements =
        subjects.stream()
            .map(
                subject ->
                    TicketSubject.create(
                        uuidGenerator.generate(),
                        ticketId,
                        subject.subjectType(),
                        subject.subjectRefId(),
                        subject.roleKey(),
                        subject.sourceField(),
                        now))
            .toList();
    Set<SubjectIdentity> identities = new HashSet<>();
    for (TicketSubject subject : replacements) {
      SubjectIdentity identity =
          new SubjectIdentity(
              subject.getSubjectType(), subject.getSubjectRefId(), subject.getRoleKey());
      if (!identities.add(identity)) {
        throw new CommandConflictException(
            "DUPLICATE_TICKET_SUBJECT", "A Ticket subject identity must be unique");
      }
    }

    subjectRepository.deleteAllByTicketId(ticketId);
    subjectRepository.flush();
    subjectRepository.saveAll(replacements);
    subjectRepository.flush();
  }

  private TicketDtos.AggregateView aggregate(Ticket ticket) {
    List<Event> events = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticket.getId());
    UUID currentEventId = events.isEmpty() ? null : events.get(events.size() - 1).getId();
    return new TicketDtos.AggregateView(
        TicketDtos.TicketView.from(ticket),
        revisionRepository.findAllByTicketIdOrderByRevisionNoAsc(ticket.getId()).stream()
            .map(TicketDtos.RevisionView::from)
            .toList(),
        subjectRepository.findAllByTicketIdOrderByCreatedAtAsc(ticket.getId()).stream()
            .map(TicketDtos.SubjectView::from)
            .toList(),
        currentEventId);
  }

  private record SubjectIdentity(String subjectType, UUID subjectRefId, String roleKey) {}
}
