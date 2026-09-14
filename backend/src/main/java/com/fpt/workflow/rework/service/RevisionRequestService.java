package com.fpt.workflow.rework.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.rework.domain.*;
import com.fpt.workflow.rework.repository.*;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.domain.TicketRevision;
import com.fpt.workflow.ticket.repository.TicketRepository;
import com.fpt.workflow.ticket.repository.TicketRevisionRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ticket-scoped REQUEST_REVISION lifecycle. Runtime fields never mutate a WorkflowVersion. */
@Service
public class RevisionRequestService {
  private static final Set<EventStatus> TERMINAL_EVENTS =
      EnumSet.of(
          EventStatus.COMPLETED, EventStatus.FAILED, EventStatus.CANCELLED, EventStatus.TERMINATED);
  private final RevisionRequestRepository requests;
  private final RevisionRequestedFieldRepository fields;
  private final RevisionRequestedValueRepository values;
  private final TaskExecutionRepository tasks;
  private final NodeExecutionRepository executions;
  private final EventRepository events;
  private final NodeDefinitionRepository nodes;
  private final EdgeDefinitionRepository edges;
  private final TicketRepository tickets;
  private final TicketRevisionRepository revisions;
  private final RoutingService routingService;
  private final AuditEventRepository audits;
  private final ActorContextProvider actors;
  private final UuidGenerator uuids;
  private final PlatformClock clock;
  private RevisionInputRemapPort inputRemaps;

  /**
   * Category-owned remap of the pinned mapping contract. Optional: legacy (non-category) runtime
   * revisions keep the historical TicketRevision-only behavior.
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setInputRemaps(RevisionInputRemapPort inputRemaps) {
    this.inputRemaps = inputRemaps;
  }

  public RevisionRequestService(
      RevisionRequestRepository requests,
      RevisionRequestedFieldRepository fields,
      RevisionRequestedValueRepository values,
      TaskExecutionRepository tasks,
      NodeExecutionRepository executions,
      EventRepository events,
      NodeDefinitionRepository nodes,
      EdgeDefinitionRepository edges,
      TicketRepository tickets,
      TicketRevisionRepository revisions,
      RoutingService routingService,
      AuditEventRepository audits,
      ActorContextProvider actors,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.requests = requests;
    this.fields = fields;
    this.values = values;
    this.tasks = tasks;
    this.executions = executions;
    this.events = events;
    this.nodes = nodes;
    this.edges = edges;
    this.tickets = tickets;
    this.revisions = revisions;
    this.routingService = routingService;
    this.audits = audits;
    this.actors = actors;
    this.uuids = uuids;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public RevisionRequest require(UUID requestId) {
    return requests
        .findById(requestId)
        .orElseThrow(() -> new IllegalArgumentException("Revision request not found: " + requestId));
  }

  /** Replay-stable projection of the revision chain produced by the submit command. */
  public com.fpt.workflow.rework.dto.RevisionRequestDtos.RevisionSubmitView view(
      RevisionRequest request) {
    Event event = events.findById(request.getEventId()).orElseThrow();
    Ticket ticket = tickets.findById(event.getTicketId()).orElseThrow();
    return new com.fpt.workflow.rework.dto.RevisionRequestDtos.RevisionSubmitView(
        ticket.getId(),
        ticket.getDataRevision(),
        ticket.getCurrentRevisionId(),
        request.getFormSubmissionId(),
        request.getTicketRevisionId(),
        request.getInputRevision());
  }

  @Transactional
  public RevisionRequest open(
      UUID sourceTaskId,
      UUID targetNodeId,
      String comment,
      List<FieldSpec> requestedFields,
      CorrelationId correlationId,
      CommandId commandId) {
    ActorContext actor = actors.requireActor();
    TaskExecution task =
        tasks
            .findByIdForUpdate(sourceTaskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + sourceTaskId));
    requireActiveTaskActor(task, actor);
    NodeExecution source =
        executions
            .findById(task.getNodeExecutionId())
            .orElseThrow(() -> new IllegalStateException("Source NodeExecution missing"));
    Event event = events.findById(source.getEventId()).orElseThrow();
    if (TERMINAL_EVENTS.contains(event.getStatus()))
      throw new IllegalStateException("Event is terminal");
    NodeDefinition sourceNode = nodes.findById(source.getNodeDefinitionId()).orElseThrow();
    requireConfiguredAction(sourceNode);
    List<com.fpt.workflow.definition.domain.EdgeDefinition> validReworkEdges =
        edges.findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(event.getWorkflowVersionId()).stream()
            .filter(
                edge ->
                    edge.getSourceNodeId().equals(sourceNode.getId())
                        && "REVISION_REQUESTED".equalsIgnoreCase(edge.getSourcePort())
                        && (edge.getTransitionType() == TransitionType.REWORK
                            || edge.getTransitionType() == TransitionType.RETURN))
            .toList();
    UUID resolvedTargetNodeId;
    if (targetNodeId == null) {
      List<UUID> configuredTargets =
          validReworkEdges.stream().map(edge -> edge.getTargetNodeId()).distinct().toList();
      if (configuredTargets.size() != 1) {
        throw new IllegalArgumentException(
            configuredTargets.isEmpty()
                ? "REQUEST_REVISION requires an explicit REWORK/RETURN edge"
                : "targetNodeId is required when REQUEST_REVISION has multiple rework targets");
      }
      resolvedTargetNodeId = configuredTargets.getFirst();
    } else {
      resolvedTargetNodeId = targetNodeId;
    }
    NodeDefinition target = nodes.findById(resolvedTargetNodeId).orElseThrow();
    if (!target.getWorkflowVersionId().equals(event.getWorkflowVersionId()))
      throw new IllegalArgumentException("Revision target is outside the Event WorkflowVersion");
    boolean validRework =
        validReworkEdges.stream()
            .anyMatch(edge -> edge.getTargetNodeId().equals(resolvedTargetNodeId));
    if (!validRework)
      throw new IllegalArgumentException("Revision target requires an explicit REWORK/RETURN edge");

    Instant now = clock.now();
    RevisionRequest request =
        requests.saveAndFlush(
            RevisionRequest.open(
                uuids.generate(),
                event.getId(),
                task.getId(),
                actor.actorId(),
                resolvedTargetNodeId,
                source.getCycleId(),
                comment,
                now));
    List<FieldSpec> specs = requestedFields == null ? List.of() : List.copyOf(requestedFields);
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < specs.size(); i++) {
      FieldSpec spec = Objects.requireNonNull(specs.get(i));
      if (!keys.add(spec.key()))
        throw new IllegalArgumentException("Duplicate requested field: " + spec.key());
      fields.save(
          RevisionRequestedField.create(
              uuids.generate(),
              request.getId(),
              spec.key(),
              spec.label(),
              spec.type(),
              spec.required(),
              i,
              spec.sensitive(),
              spec.schema()));
    }
    fields.flush();
    audit(request, "REVISION_REQUESTED", actor, correlationId, commandId, now);
    return request;
  }

  @Transactional
  public NodeExecution submit(
      UUID requestId,
      Map<String, JsonNode> submittedValues,
      JsonNode replacementTicketData,
      String changeReason,
      CorrelationId correlationId,
      CommandId commandId) {
    return submit(
        requestId, submittedValues, replacementTicketData, changeReason, null, correlationId, commandId);
  }

  @Transactional
  public NodeExecution submit(
      UUID requestId,
      Map<String, JsonNode> submittedValues,
      JsonNode replacementTicketData,
      String changeReason,
      Long expectedVersion,
      CorrelationId correlationId,
      CommandId commandId) {
    ActorContext actor = actors.requireActor();
    RevisionRequest request =
        requests
            .findByIdForUpdate(requestId)
            .orElseThrow(
                () -> new IllegalArgumentException("Revision request not found: " + requestId));
    if (request.getStatus() != RevisionRequestStatus.OPEN)
      throw new IllegalStateException("Revision request is terminal");
    Event event = events.findByIdForUpdate(request.getEventId()).orElseThrow();
    if (TERMINAL_EVENTS.contains(event.getStatus()))
      throw new IllegalStateException("Event is terminal");
    Ticket ticket = tickets.findByIdForUpdate(event.getTicketId()).orElseThrow();
    if (!ticket.getCreatorId().equals(actor.actorId()) && !actor.hasRole(RoleKey.ADMIN))
      throw new AccessDeniedException("Only the Ticket creator can submit a revision");
    // Optimistic locking is verified AFTER authorization so an unauthorized caller cannot probe
    // the request's current version through a 409 STALE_EXPECTED_VERSION response.
    if (expectedVersion != null && request.getLockVersion() != expectedVersion) {
      throw new com.fpt.workflow.shared.api.CommandConflictException(
          "STALE_EXPECTED_VERSION", "RevisionRequest lock version does not match If-Match");
    }
    Map<String, JsonNode> supplied =
        submittedValues == null ? Map.of() : Map.copyOf(submittedValues);
    List<RevisionRequestedField> definitions =
        fields.findAllByRevisionRequestIdOrderByOrdinalAsc(requestId);
    Set<String> declared = new HashSet<>();
    Instant now = clock.now();
    for (RevisionRequestedField field : definitions) {
      declared.add(field.getFieldKey());
      JsonNode value = supplied.get(field.getFieldKey());
      if ((value == null || value.isNull()) && field.isRequired())
        throw new IllegalArgumentException(
            "Required revision field is missing: " + field.getFieldKey());
      if (value == null) value = JsonNodeFactory.instance.nullNode();
      validateValue(field, value);
      values.save(RevisionRequestedValue.create(field.getId(), value, actor.actorId(), now));
    }
    if (!declared.containsAll(supplied.keySet()))
      throw new IllegalArgumentException("Undeclared runtime requested field supplied");
    values.flush();

    if (replacementTicketData != null && !replacementTicketData.equals(ticket.getDataJson())) {
      if (!replacementTicketData.isObject())
        throw new IllegalArgumentException("Ticket data must be an object");
      TicketRevision previous = revisions.findById(ticket.getCurrentRevisionId()).orElseThrow();
      long revisionNo = ticket.nextRevisionNo();
      TicketRevision revision =
          TicketRevision.create(
              uuids.generate(),
              ticket.getId(),
              revisionNo,
              replacementTicketData,
              previous.getSourceSchemaVersion(),
              previous.getSchemaChecksum(),
              actor.actorId(),
              now,
              requireReason(changeReason));
      ticket.recordBusinessRevision(revision.getId(), revisionNo, replacementTicketData, now);
      revisions.save(revision);
      tickets.save(ticket);
      // §7.5: where the pinned Category policy permits remapping, rerun the SAME mapping contract
      // and append a NEW immutable WorkflowInputRevision. The Event keeps its exact
      // WorkflowVersion; the new rework occurrence sees the new revision, old occurrences keep
      // their already-resolved inputs.
      if (inputRemaps != null) {
        inputRemaps
            .remap(event.getId(), event.getWorkflowVersionId(), ticket.getId(),
                revision.getId(), replacementTicketData, actor.actorId(), now)
            .ifPresent(
                remapped ->
                    request.bindRemap(revision.getId(), remapped.formSubmissionId(), remapped.inputRevision()));
        requests.saveAndFlush(request);
      }
    }
    request.submit(now);
    requests.saveAndFlush(request);
    audit(request, "REVISION_RESUBMITTED", actor, correlationId, commandId, now);
    TaskExecution sourceTask =
        tasks.findByIdForUpdate(request.getSourceTaskId()).orElseThrow();
    NodeExecution source =
        executions.findByIdForUpdate(sourceTask.getNodeExecutionId()).orElseThrow();
    ObjectNode routingOutput = JsonNodeFactory.instance.objectNode();
    routingOutput.put("revisionRequestId", request.getId().toString());
    routingOutput.put("targetNodeId", request.getTargetNodeId().toString());
    routingOutput.put("submittedBy", actor.actorId().toString());
    if (source.getStatus() != NodeExecutionStatus.COMPLETED) {
      source.complete("REVISION_REQUESTED", routingOutput, now);
      executions.saveAndFlush(source);
    }
    if (sourceTask.getStatus() != TaskStatus.COMPLETED
        && sourceTask.getStatus() != TaskStatus.CANCELLED
        && sourceTask.getStatus() != TaskStatus.EXPIRED) {
      sourceTask.cancel(
          com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome.of("REVISION_REQUESTED"), now);
      tasks.saveAndFlush(sourceTask);
    }

    RoutingResult routed = routingService.route(source.getId(), correlationId, commandId);
    if (routed.activations().isEmpty()) {
      Event refreshedEvent = events.findById(event.getId()).orElse(event);
      if (refreshedEvent.getStatus() == EventStatus.FAILED) {
        throw new IllegalStateException("REWORK_ITERATION_LIMIT_EXCEEDED");
      }
    }
    return routed.activations().stream()
        .filter(activation -> activation.getNodeDefinitionId().equals(request.getTargetNodeId()))
        .findFirst()
        .orElseGet(
            () ->
                routed.activations().stream()
                    .findFirst()
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "Revision submission produced no downstream activation")));
  }

  private void requireActiveTaskActor(TaskExecution task, ActorContext actor) {
    if (task.getStatus() == TaskStatus.COMPLETED
        || task.getStatus() == TaskStatus.CANCELLED
        || task.getStatus() == TaskStatus.EXPIRED)
      throw new IllegalStateException("REQUEST_REVISION requires an active task");
    if (!Objects.equals(task.getAssigneeId(), actor.actorId()) && !actor.hasRole(RoleKey.ADMIN))
      throw new AccessDeniedException("Actor is not authorized for this task");
  }

  private void requireConfiguredAction(NodeDefinition node) {
    JsonNode actions = node.getConfigJson().path("allowedActions");
    if (!actions.isArray()
        || !java.util.stream.StreamSupport.stream(actions.spliterator(), false)
            .anyMatch(value -> "REQUEST_REVISION".equals(value.asText())))
      throw new AccessDeniedException("REQUEST_REVISION is not enabled by the task configuration");
  }

  private void validateValue(RevisionRequestedField field, JsonNode value) {
    if (value.isNull()) return;
    boolean valid =
        switch (field.getType()) {
          case TEXT, TEXTAREA -> value.isTextual();
          case NUMBER -> value.isNumber() && new BigDecimal(value.asText()) != null;
          case BOOLEAN -> value.isBoolean();
          case DATE -> parseDate(value);
          case DATETIME -> parseInstant(value);
          case SELECT -> value.isTextual() && selectAllows(field.getSchemaJson(), value.asText());
          case FILE -> value.isTextual() || value.isObject();
          case FILE_LIST ->
              value.isArray()
                  && java.util.stream.StreamSupport.stream(value.spliterator(), false)
                      .allMatch(item -> item.isTextual() || item.isObject());
        };
    if (!valid)
      throw new IllegalArgumentException("Invalid value for revision field " + field.getFieldKey());
  }

  private boolean parseDate(JsonNode value) {
    try {
      LocalDate.parse(value.asText());
      return value.isTextual();
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private boolean parseInstant(JsonNode value) {
    try {
      Instant.parse(value.asText());
      return value.isTextual();
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private boolean selectAllows(JsonNode schema, String value) {
    JsonNode options = schema.path("options");
    return options.isArray()
        && java.util.stream.StreamSupport.stream(options.spliterator(), false)
            .anyMatch(
                option ->
                    value.equals(
                        option.isTextual() ? option.asText() : option.path("value").asText()));
  }

  private String requireReason(String reason) {
    if (reason == null || reason.isBlank())
      throw new IllegalArgumentException("Ticket revision reason is required");
    return reason.trim();
  }

  private void audit(
      RevisionRequest request,
      String type,
      ActorContext actor,
      CorrelationId correlationId,
      CommandId commandId,
      Instant now) {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("eventId", request.getEventId().toString());
    metadata.put("sourceTaskId", request.getSourceTaskId().toString());
    metadata.put("targetNodeId", request.getTargetNodeId().toString());
    audits.save(
        AuditEvent.record(
            uuids.generate(),
            "REVISION_REQUEST",
            request.getId(),
            type,
            actor.actorId(),
            actor.actorId(),
            correlationId,
            commandId,
            metadata,
            now));
  }

  public record FieldSpec(
      String key,
      String label,
      RuntimeRequestedFieldType type,
      boolean required,
      boolean sensitive,
      JsonNode schema) {}
}
