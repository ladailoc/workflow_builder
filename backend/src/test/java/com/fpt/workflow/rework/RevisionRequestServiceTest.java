package com.fpt.workflow.rework;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.*;
import com.fpt.workflow.definition.repository.*;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.rework.domain.*;
import com.fpt.workflow.rework.repository.*;
import com.fpt.workflow.rework.service.RevisionRequestService;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.*;
import com.fpt.workflow.runtime.repository.*;
import com.fpt.workflow.security.*;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.*;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.ticket.domain.*;
import com.fpt.workflow.ticket.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RevisionRequestServiceTest {
  private final RevisionRequestRepository requests = mock(RevisionRequestRepository.class);
  private final RevisionRequestedFieldRepository fields =
      mock(RevisionRequestedFieldRepository.class);
  private final RevisionRequestedValueRepository values =
      mock(RevisionRequestedValueRepository.class);
  private final TaskExecutionRepository tasks = mock(TaskExecutionRepository.class);
  private final NodeExecutionRepository executions = mock(NodeExecutionRepository.class);
  private final EventRepository events = mock(EventRepository.class);
  private final NodeDefinitionRepository nodes = mock(NodeDefinitionRepository.class);
  private final EdgeDefinitionRepository edges = mock(EdgeDefinitionRepository.class);
  private final TicketRepository tickets = mock(TicketRepository.class);
  private final TicketRevisionRepository revisions = mock(TicketRevisionRepository.class);
  private final NodeActivationService activation = mock(NodeActivationService.class);
  private final AuditEventRepository audits = mock(AuditEventRepository.class);
  private final ActorContextProvider actors = mock(ActorContextProvider.class);
  private final AtomicReference<ActorContext> currentActor = new AtomicReference<>();
  private final Instant now = Instant.parse("2026-09-08T06:00:00Z");
  private RevisionRequestService service;
  private UUID creator, reviewer, versionId, eventId, taskId, sourceNodeId, targetNodeId;
  private RevisionRequest request;
  private Ticket ticket;
  private NodeExecution source;

  @BeforeEach
  void setup() {
    creator = UUID.randomUUID();
    reviewer = UUID.randomUUID();
    versionId = UUID.randomUUID();
    eventId = UUID.randomUUID();
    taskId = UUID.randomUUID();
    sourceNodeId = UUID.randomUUID();
    targetNodeId = UUID.randomUUID();
    UuidGenerator uuids = UUID::randomUUID;
    PlatformClock clock = () -> now;
    when(actors.requireActor()).thenAnswer(inv -> currentActor.get());
    when(requests.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(fields.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(values.save(any())).thenAnswer(inv -> inv.getArgument(0));
    service =
        new RevisionRequestService(
            requests,
            fields,
            values,
            tasks,
            executions,
            events,
            nodes,
            edges,
            tickets,
            revisions,
            activation,
            audits,
            actors,
            uuids,
            clock);

    UUID ticketId = UUID.randomUUID(), revisionId = UUID.randomUUID();
    ticket =
        Ticket.createDraft(
            ticketId, UUID.randomUUID(), creator, object().put("score", 1), now.minusSeconds(10));
    ticket.submit(revisionId, 1, ticket.getDataJson(), now.minusSeconds(9));
    Event event =
        Event.createRoot(
            eventId,
            ticketId,
            versionId,
            revisionId,
            null,
            null,
            "TEST",
            "test",
            object(),
            creator,
            now.minusSeconds(8));
    event.markRunning();
    source =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            sourceNodeId,
            "source",
            UUID.randomUUID(),
            0,
            "root",
            "employee-B",
            null,
            null,
            object(),
            revisionId,
            now.minusSeconds(7));
    source.markReady();
    source.start(now.minusSeconds(6));
    source.waitFor(RuntimeWaitReason.HUMAN_TASK);
    TaskExecution task =
        TaskExecution.create(
            taskId,
            source.getId(),
            null,
            reviewer,
            "Review",
            null,
            null,
            object(),
            0,
            null,
            now.minusSeconds(5));
    ObjectNode config = object();
    config.set("participant", object());
    config.set("allowedActions", JsonNodeFactory.instance.arrayNode().add("REQUEST_REVISION"));
    NodeDefinition sourceNode = node(sourceNodeId, "approval", config);
    NodeDefinition target = node(targetNodeId, "manager-review", object());
    EdgeDefinition edge =
        EdgeDefinition.create(
            UUID.randomUUID(),
            versionId,
            sourceNodeId,
            "REVISION_REQUESTED",
            targetNodeId,
            null,
            0,
            true,
            TransitionType.REWORK,
            "return",
            object());
    when(tasks.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
    when(tasks.findById(taskId)).thenReturn(Optional.of(task));
    when(executions.findById(source.getId())).thenReturn(Optional.of(source));
    when(events.findById(eventId)).thenReturn(Optional.of(event));
    when(events.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
    when(nodes.findById(sourceNodeId)).thenReturn(Optional.of(sourceNode));
    when(nodes.findById(targetNodeId)).thenReturn(Optional.of(target));
    when(edges.findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(versionId))
        .thenReturn(List.of(edge));
    when(tickets.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));
    when(revisions.findById(revisionId))
        .thenReturn(
            Optional.of(
                TicketRevision.create(
                    revisionId,
                    ticketId,
                    1,
                    ticket.getDataJson(),
                    "v1",
                    "checksum",
                    creator,
                    now.minusSeconds(9),
                    null)));
  }

  @Test
  void fullRevisionResubmissionCreatesTicketRevisionAndNewOccurrence() {
    currentActor.set(actor(reviewer));
    request =
        service.open(
            taskId,
            targetNodeId,
            "Please clarify",
            List.of(
                new RevisionRequestService.FieldSpec(
                    "clarification",
                    "Clarification",
                    RuntimeRequestedFieldType.TEXT,
                    true,
                    false,
                    object())),
            correlation(),
            command());
    ArgumentCaptor<RevisionRequestedField> fieldCaptor =
        ArgumentCaptor.forClass(RevisionRequestedField.class);
    verify(fields).save(fieldCaptor.capture());
    when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
    when(fields.findAllByRevisionRequestIdOrderByOrdinalAsc(request.getId()))
        .thenReturn(List.of(fieldCaptor.getValue()));
    NodeExecution next =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            targetNodeId,
            "revision:" + request.getId(),
            UUID.randomUUID(),
            1,
            "root",
            "employee-B",
            null,
            null,
            object(),
            ticket.getCurrentRevisionId(),
            now);
    when(activation.activate(any())).thenReturn(next);

    currentActor.set(actor(creator));
    JsonNode changed = object().put("score", 2);
    NodeExecution result =
        service.submit(
            request.getId(),
            Map.of("clarification", JsonNodeFactory.instance.textNode("updated evidence")),
            changed,
            "Requested correction",
            correlation(),
            command());

    assertThat(request.getStatus()).isEqualTo(RevisionRequestStatus.SUBMITTED);
    assertThat(ticket.getDataRevision()).isEqualTo(2);
    assertThat(result).isSameAs(next);
    verify(revisions)
        .save(
            argThat(
                revision ->
                    revision.getRevisionNo() == 2
                        && revision.getDataSnapshotJson().equals(changed)));
    verify(values).save(argThat(value -> value.getValueJson().asText().equals("updated evidence")));
    verify(activation)
        .activate(
            argThat(
                activationRequest ->
                    activationRequest.iteration() == 1
                        && activationRequest.itemToken().equals("employee-B")
                        && activationRequest.targetNodeDefinitionId().equals(targetNodeId)));
    assertThat(source.getStatus().name()).isEqualTo("WAITING");
  }

  @Test
  void disallowsUndeclaredAndForbiddenRuntimeFieldTypesByClosedEnum() {
    assertThat(Arrays.stream(RuntimeRequestedFieldType.values()).map(Enum::name))
        .containsExactly(
            "TEXT",
            "TEXTAREA",
            "NUMBER",
            "DATE",
            "DATETIME",
            "SELECT",
            "BOOLEAN",
            "FILE",
            "FILE_LIST")
        .doesNotContain("USER", "ROLE", "DEPARTMENT");
  }

  @Test
  void onlyConfiguredActiveTaskActorCanRequestRevision() {
    currentActor.set(actor(creator));
    assertThatThrownBy(
            () -> service.open(taskId, targetNodeId, null, List.of(), correlation(), command()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  private NodeDefinition node(UUID id, String key, JsonNode config) {
    return NodeDefinition.create(
        id, versionId, key, "REVIEW", key, null, 1, config, null, null, object());
  }

  private ActorContext actor(UUID id) {
    return new ActorContext(id, id.toString(), Set.of(RoleKey.USER), Set.of());
  }

  private CorrelationId correlation() {
    return new CorrelationId(UUID.randomUUID());
  }

  private CommandId command() {
    return new CommandId(UUID.randomUUID());
  }

  private static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }
}
