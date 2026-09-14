package com.fpt.workflow.ticket.service;

import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.operations.job.WorkflowJobTransactions;
import com.fpt.workflow.operations.outbox.OutboxTransactions;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.trigger.EventTriggerService;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.security.visibility.VisibilityResolver;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import org.springframework.transaction.annotation.Transactional;
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
  private final WorkflowJobTransactions jobs;
  private final OutboxTransactions outbox;
  private final com.fpt.workflow.runtime.lifecycle.EventLifecycleService eventLifecycleService;
  private final VisibilityResolver visibilityResolver;
  private final TicketMutationBoundary ticketMutationBoundary;
  private final EventTriggerService eventTriggers;

  public TicketService(
      TicketRepository ticketRepository,
      TicketRevisionRepository revisionRepository,
      TicketSubjectRepository subjectRepository,
      RequestTypeRepository requestTypeRepository,
      TicketFormValidationService ticketFormValidationService,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      EventRepository eventRepository,
      WorkflowJobTransactions jobs,
      VisibilityResolver visibilityResolver) {
    this(
        ticketRepository,
        revisionRepository,
        subjectRepository,
        requestTypeRepository,
        ticketFormValidationService,
        actorContextProvider,
        uuidGenerator,
        clock,
        eventRepository,
        jobs,
        null,
        visibilityResolver,
        null,
        null,
        null);
  }

  public TicketService(
      TicketRepository ticketRepository,
      TicketRevisionRepository revisionRepository,
      TicketSubjectRepository subjectRepository,
      RequestTypeRepository requestTypeRepository,
      TicketFormValidationService ticketFormValidationService,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      EventRepository eventRepository,
      WorkflowJobTransactions jobs,
      com.fpt.workflow.runtime.lifecycle.EventLifecycleService eventLifecycleService,
      VisibilityResolver visibilityResolver,
      TicketMutationBoundary ticketMutationBoundary) {
    this(
        ticketRepository,
        revisionRepository,
        subjectRepository,
        requestTypeRepository,
        ticketFormValidationService,
        actorContextProvider,
        uuidGenerator,
        clock,
        eventRepository,
        jobs,
        eventLifecycleService,
        visibilityResolver,
        ticketMutationBoundary,
        null,
        null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public TicketService(
      TicketRepository ticketRepository,
      TicketRevisionRepository revisionRepository,
      TicketSubjectRepository subjectRepository,
      RequestTypeRepository requestTypeRepository,
      TicketFormValidationService ticketFormValidationService,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      EventRepository eventRepository,
      WorkflowJobTransactions jobs,
      @org.springframework.context.annotation.Lazy
          com.fpt.workflow.runtime.lifecycle.EventLifecycleService eventLifecycleService,
      VisibilityResolver visibilityResolver,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          TicketMutationBoundary ticketMutationBoundary,
      OutboxTransactions outbox,
      EventTriggerService eventTriggers) {
    this.ticketRepository = ticketRepository;
    this.revisionRepository = revisionRepository;
    this.subjectRepository = subjectRepository;
    this.requestTypeRepository = requestTypeRepository;
    this.ticketFormValidationService = ticketFormValidationService;
    this.actorContextProvider = actorContextProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.eventRepository = eventRepository;
    this.jobs = jobs;
    this.outbox = outbox;
    this.eventTriggers = eventTriggers;
    this.eventLifecycleService = eventLifecycleService;
    this.visibilityResolver = visibilityResolver;
    this.ticketMutationBoundary =
        ticketMutationBoundary != null
            ? ticketMutationBoundary
            : new TicketMutationBoundary(eventRepository);
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
    ticketMutationBoundary.requireCanMutateTicket(ticketId);
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
    ticketMutationBoundary.requireCanMutateTicket(ticketId);
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
    Event event =
        createRootEvent(
            ticket.getId(),
            formContract.workflowVersionId(),
            revision.getId(),
            null,
            null,
            "TICKET_SUBMIT",
            commandId.toString(),
            actor.actorId(),
            now);
    UUID cycleId = uuidGenerator.generate();
    UUID correlationId = uuidGenerator.generate();
    jobs.enqueue(
        "EVENT_START",
        "EVENT",
        event.getId(),
        objectNode()
            .put("eventId", event.getId().toString())
            .put("cycleId", cycleId.toString())
            .put("correlationId", correlationId.toString())
            .put("commandId", commandId.toString()),
        5,
        now,
        "event-start:" + event.getId());
    enqueueTicketEvent("TICKET_SUBMITTED", ticket, event, revision, commandId);
    return aggregate(ticket);
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView cancel(
      UUID ticketId, ExpectedVersion expectedVersion, TicketDtos.CancelTicket request) {
    return cancel(ticketId, expectedVersion, request, new CommandId(uuidGenerator.generate()));
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView cancel(
      UUID ticketId,
      UUID callingEventId,
      ExpectedVersion expectedVersion,
      TicketDtos.CancelTicket request,
      CommandId commandId) {
    ticketMutationBoundary.requireCanMutateTicket(ticketId, callingEventId);
    return cancel(ticketId, expectedVersion, request, commandId);
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView cancel(
      UUID ticketId,
      ExpectedVersion expectedVersion,
      TicketDtos.CancelTicket request,
      CommandId commandId) {
    ticketMutationBoundary.requireCanMutateTicket(ticketId);
    Ticket ticket = requireOwnedTicket(ticketId);
    if (expectedVersion != null) {
      requireExpectedVersion(ticket, expectedVersion);
    }
    Instant now = clock.now();
    ticket.cancel(now);
    ticketRepository.saveAndFlush(ticket);

    List<Event> events = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId);
    String reason =
        (request != null && request.reason() != null && !request.reason().isBlank())
            ? request.reason().trim()
            : "Ticket cancelled";
    if (eventLifecycleService != null) {
      for (Event event : events) {
        if (!com.fpt.workflow.runtime.lifecycle.EventLifecycleService.TERMINAL_EVENT_STATUSES
            .contains(event.getStatus())) {
          eventLifecycleService.cancelEvent(
              event.getId(),
              commandId != null ? commandId : new CommandId(uuidGenerator.generate()),
              new com.fpt.workflow.shared.domain.CorrelationId(uuidGenerator.generate()),
              reason);
        }
      }
    }
    return aggregate(ticket);
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView reopen(
      UUID ticketId, ExpectedVersion expectedVersion, TicketDtos.ReopenTicket request) {
    return reopen(ticketId, expectedVersion, request, new CommandId(uuidGenerator.generate()));
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView reopen(
      UUID ticketId,
      ExpectedVersion expectedVersion,
      TicketDtos.ReopenTicket request,
      CommandId commandId) {
    ticketMutationBoundary.requireCanMutateTicket(ticketId);
    Ticket ticket = requireOwnedTicket(ticketId);
    if (expectedVersion != null) {
      requireExpectedVersion(ticket, expectedVersion);
    }
    if (ticket.getStatus() != TicketStatus.COMPLETED
        && ticket.getStatus() != TicketStatus.REJECTED
        && ticket.getStatus() != TicketStatus.CANCELLED) {
      throw new IllegalStateException(
          "Reopen requires a terminal Ticket; current status is " + ticket.getStatus());
    }
    ActorContext actor = actorContextProvider.requireActor();
    Instant now = clock.now();
    ticket.reopen(now);
    ticketRepository.saveAndFlush(ticket);

    List<Event> previousEvents = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId);
    Event lastEvent =
        previousEvents.isEmpty() ? null : previousEvents.get(previousEvents.size() - 1);
    RequestType requestType = requireActiveRequestType(ticket.getRequestTypeId());
    UUID versionId = null;
    try {
      versionId = ticketFormValidationService.currentContract(requestType).workflowVersionId();
    } catch (Exception ignored) {
      if (lastEvent != null) {
        versionId = lastEvent.getWorkflowVersionId();
      }
    }
    if (versionId == null && lastEvent != null) {
      versionId = lastEvent.getWorkflowVersionId();
    }
    UUID previousEventId = lastEvent != null ? lastEvent.getId() : null;
    UUID restartedFromId =
        lastEvent != null
            ? (lastEvent.getRestartedFromEventId() != null
                ? lastEvent.getRestartedFromEventId()
                : lastEvent.getId())
            : null;

    Event event =
        createRootEvent(
            ticket.getId(),
            versionId,
            ticket.getCurrentRevisionId(),
            previousEventId,
            restartedFromId,
            "TICKET_REOPEN",
            commandId != null ? commandId.toString() : null,
            actor.actorId(),
            now);

    UUID cycleId = uuidGenerator.generate();
    UUID correlationId = uuidGenerator.generate();
    jobs.enqueue(
        "EVENT_START",
        "EVENT",
        event.getId(),
        objectNode()
            .put("eventId", event.getId().toString())
            .put("cycleId", cycleId.toString())
            .put("correlationId", correlationId.toString())
            .put(
                "commandId",
                commandId != null ? commandId.toString() : uuidGenerator.generate().toString()),
        5,
        now,
        "event-start:" + event.getId());
    return aggregate(ticket);
  }

  private void enqueueTicketEvent(
      String eventType,
      Ticket ticket,
      Event event,
      TicketRevision revision,
      CommandId commandId) {
    // Legacy unit-test constructors do not provide the production outbox collaborator.
    if (outbox == null) return;
    outbox.enqueue(
        eventType,
        "TICKET",
        ticket.getId(),
        objectNode()
            .put("ticketId", ticket.getId().toString())
            .put("eventId", event.getId().toString())
            .put("workflowVersionId", event.getWorkflowVersionId().toString())
            .put("revisionId", revision.getId().toString())
            .put("commandId", commandId != null ? commandId.toString() : null),
        5,
        eventType.toLowerCase(java.util.Locale.ROOT) + ":" + event.getId());
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView resubmit(
      UUID ticketId, ExpectedVersion expectedVersion, TicketDtos.ResubmitTicket request) {
    return resubmit(ticketId, expectedVersion, request, new CommandId(uuidGenerator.generate()));
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView resubmit(
      UUID ticketId,
      ExpectedVersion expectedVersion,
      TicketDtos.ResubmitTicket request,
      CommandId commandId) {
    Objects.requireNonNull(request, "request");
    ticketMutationBoundary.requireCanMutateTicket(ticketId);
    Ticket ticket = requireOwnedTicket(ticketId);
    if (expectedVersion != null) {
      requireExpectedVersion(ticket, expectedVersion);
    }
    if (ticket.getStatus() != TicketStatus.COMPLETED
        && ticket.getStatus() != TicketStatus.REJECTED
        && ticket.getStatus() != TicketStatus.CANCELLED
        && ticket.getStatus() != TicketStatus.DRAFT) {
      throw new IllegalStateException(
          "Resubmit requires a terminal or draft Ticket; current status is " + ticket.getStatus());
    }
    ActorContext actor = actorContextProvider.requireActor();
    RequestType requestType = requireActiveRequestType(ticket.getRequestTypeId());
    TicketFormContract currentContract = ticketFormValidationService.currentContract(requestType);
    UUID sourceVersionId =
        request.sourceWorkflowVersionId() != null
            ? request.sourceWorkflowVersionId()
            : currentContract.workflowVersionId();
    String sourceChecksum =
        request.schemaChecksum() != null
            ? request.schemaChecksum()
            : currentContract.schemaChecksum();
    TicketFormContract formContract =
        ticketFormValidationService.validateSubmission(
            requestType,
            request.dataJson() != null ? request.dataJson() : ticket.getDataJson(),
            actor,
            sourceVersionId,
            sourceChecksum);
    Instant now = clock.now();
    long revisionNo = ticket.nextRevisionNo();
    TicketRevision revision =
        TicketRevision.create(
            uuidGenerator.generate(),
            ticket.getId(),
            revisionNo,
            request.dataJson() != null ? request.dataJson() : ticket.getDataJson(),
            formContract.workflowVersionId().toString(),
            formContract.schemaChecksum(),
            actor.actorId(),
            now,
            request.changeReason());
    revisionRepository.saveAndFlush(revision);

    ticket.resubmit(revision.getId(), revisionNo, revision.getDataSnapshotJson(), now);
    ticketRepository.saveAndFlush(ticket);
    if (request.subjects() != null) {
      replaceSubjects(ticketId, request.subjects(), now);
    }

    List<Event> previousEvents = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId);
    Event lastEvent =
        previousEvents.isEmpty() ? null : previousEvents.get(previousEvents.size() - 1);
    UUID previousEventId = lastEvent != null ? lastEvent.getId() : null;
    UUID restartedFromId =
        lastEvent != null
            ? (lastEvent.getRestartedFromEventId() != null
                ? lastEvent.getRestartedFromEventId()
                : lastEvent.getId())
            : null;

    Event event =
        createRootEvent(
            ticket.getId(),
            formContract.workflowVersionId(),
            revision.getId(),
            previousEventId,
            restartedFromId,
            "TICKET_RESUBMIT",
            commandId != null ? commandId.toString() : null,
            actor.actorId(),
            now);

    UUID cycleId = uuidGenerator.generate();
    UUID correlationId = uuidGenerator.generate();
    jobs.enqueue(
        "EVENT_START",
        "EVENT",
        event.getId(),
        objectNode()
            .put("eventId", event.getId().toString())
            .put("cycleId", cycleId.toString())
            .put("correlationId", correlationId.toString())
            .put(
                "commandId",
                commandId != null ? commandId.toString() : uuidGenerator.generate().toString()),
        5,
        now,
        "event-start:" + event.getId());
    enqueueTicketEvent("TICKET_RESUBMITTED", ticket, event, revision, commandId);

    return aggregate(ticket);
  }

  @Transactional(readOnly = true)
  @PreAuthorize("isAuthenticated()")
  public List<Event> listTicketEvents(UUID ticketId) {
    requireOwnedTicket(ticketId);
    return eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId);
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView recordBusinessRevision(
      UUID ticketId,
      UUID callingEventId,
      ExpectedVersion expectedVersion,
      TicketDtos.RecordRevision request) {
    ticketMutationBoundary.requireCanMutateTicket(ticketId, callingEventId);
    return recordBusinessRevision(ticketId, expectedVersion, request);
  }

  @TransactionalCommand
  @PreAuthorize("isAuthenticated()")
  public TicketDtos.AggregateView recordBusinessRevision(
      UUID ticketId, ExpectedVersion expectedVersion, TicketDtos.RecordRevision request) {
    Objects.requireNonNull(request, "request");
    ticketMutationBoundary.requireCanMutateTicket(ticketId);
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
    Ticket ticket = requireTicket(ticketId);
    visibilityResolver.requireTicketVisible(actorContextProvider.requireActor(), ticket.getId());
    return aggregate(ticket);
  }

  @TransactionalQuery
  @PreAuthorize("isAuthenticated()")
  public List<TicketDtos.TicketView> listMyTickets() {
    ActorContext actor = actorContextProvider.requireActor();
    return ticketRepository.findAllByOrderByCreatedAtDesc().stream()
        .filter(ticket -> visibilityResolver.mayViewTicket(actor, ticket.getId()))
        .map(TicketDtos.TicketView::from)
        .toList();
  }

  private Ticket requireTicket(UUID ticketId) {
    return ticketRepository
        .findById(Objects.requireNonNull(ticketId, "ticketId"))
        .orElseThrow(
            () -> new ResourceNotFoundException("TICKET_NOT_FOUND", "Ticket was not found"));
  }

  private Ticket requireOwnedTicket(UUID ticketId) {
    Ticket ticket = requireTicket(ticketId);
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

  private Event createRootEvent(
      UUID ticketId,
      UUID workflowVersionId,
      UUID revisionId,
      UUID previousEventId,
      UUID restartedFromEventId,
      String triggerType,
      String triggerCorrelationKey,
      UUID startedBy,
      Instant startedAt) {
    if (eventTriggers != null) {
      return eventTriggers
          .createRoot(
              ticketId,
              workflowVersionId,
              revisionId,
              previousEventId,
              restartedFromEventId,
              triggerType,
              triggerCorrelationKey,
              objectNode(),
              startedBy,
              startedAt)
          .event();
    }
    // Compatibility for focused unit tests that use the legacy convenience constructors.
    return eventRepository.saveAndFlush(
        Event.createRoot(
            uuidGenerator.generate(),
            ticketId,
            workflowVersionId,
            revisionId,
            previousEventId,
            restartedFromEventId,
            triggerType,
            triggerCorrelationKey,
            objectNode(),
            startedBy,
            startedAt));
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
