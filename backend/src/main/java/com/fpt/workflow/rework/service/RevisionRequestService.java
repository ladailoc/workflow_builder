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
import com.fpt.workflow.runtime.activation.*;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
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
  private final NodeActivationService activationService;
  private final AuditEventRepository audits;
  private final ActorContextProvider actors;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

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
      NodeActivationService activationService,
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
    this.activationService = activationService;
    this.audits = audits;
    this.actors = actors;
    this.uuids = uuids;
    this.clock = clock;
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
    NodeDefinition target = nodes.findById(targetNodeId).orElseThrow();
    if (!target.getWorkflowVersionId().equals(event.getWorkflowVersionId()))
      throw new IllegalArgumentException("Revision target is outside the Event WorkflowVersion");
    boolean validRework =
        edges
            .findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(event.getWorkflowVersionId())
            .stream()
            .anyMatch(
                edge ->
                    edge.getSourceNodeId().equals(sourceNode.getId())
                        && edge.getTargetNodeId().equals(targetNodeId)
                        && (edge.getTransitionType() == TransitionType.REWORK
                            || edge.getTransitionType() == TransitionType.RETURN));
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
                targetNodeId,
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
    }
    request.submit(now);
    requests.saveAndFlush(request);
    audit(request, "REVISION_RESUBMITTED", actor, correlationId, commandId, now);
    NodeExecution source =
        executions
            .findById(tasks.findById(request.getSourceTaskId()).orElseThrow().getNodeExecutionId())
            .orElseThrow();
    UUID nextCycle =
        UUID.nameUUIDFromBytes(
            (request.getCycleId() + ":" + request.getId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return activationService.activate(
        new ActivationRequest(
            event.getId(),
            request.getTargetNodeId(),
            new ActivationKey("revision:" + request.getId()),
            nextCycle,
            source.getIteration() + 1,
            source.getPathToken(),
            source.getItemToken(),
            source.getSplitScopeId(),
            source.getJoinScopeId(),
            correlationId,
            commandId));
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
