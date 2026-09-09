package com.fpt.workflow.monitoring;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.context.*;
import com.fpt.workflow.runtime.domain.*;
import com.fpt.workflow.runtime.repository.*;
import com.fpt.workflow.runtime.routing.repository.RoutingDecisionRepository;
import com.fpt.workflow.security.*;
import com.fpt.workflow.task.repository.*;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class EventMonitoringServiceTest {
  @Test
  void returnsExactVersionOccurrencesAndOnlyMaskedContext() {
    var events = mock(EventRepository.class);
    var versions = mock(WorkflowVersionRepository.class);
    var nodeDefinitions = mock(NodeDefinitionRepository.class);
    var edgeDefinitions = mock(EdgeDefinitionRepository.class);
    var nodes = mock(NodeExecutionRepository.class);
    var tasks = mock(TaskExecutionRepository.class);
    var participants = mock(ParticipantSnapshotRepository.class);
    var assignments = mock(TaskAssignmentHistoryRepository.class);
    var routes = mock(RoutingDecisionRepository.class);
    var tickets = mock(TicketRepository.class);
    var contexts = mock(EventContextBuilder.class);
    var actors = mock(ActorContextProvider.class);
    var context = mock(EventContext.class);
    UUID actor = UUID.randomUUID(),
        ticketId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        versionId = UUID.randomUUID(),
        revisionId = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-08T08:00:00Z");
    Ticket ticket =
        Ticket.createDraft(
            ticketId, UUID.randomUUID(), actor, JsonNodeFactory.instance.objectNode(), now);
    ticket.submit(revisionId, 1, ticket.getDataJson(), now);
    Event event =
        Event.createRoot(
            eventId,
            ticketId,
            versionId,
            revisionId,
            null,
            null,
            "TEST",
            "monitor",
            JsonNodeFactory.instance.objectNode(),
            actor,
            now);
    event.markRunning();
    WorkflowVersion version =
        WorkflowVersion.createDraft(versionId, UUID.randomUUID(), 7, null, null, actor, now);
    version.publish(0, "exact-checksum", JsonNodeFactory.instance.objectNode(), actor, now);
    NodeExecution first =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            UUID.randomUUID(),
            "a",
            UUID.randomUUID(),
            0,
            "root",
            null,
            null,
            null,
            JsonNodeFactory.instance.objectNode(),
            revisionId,
            now);
    NodeExecution repeated =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            first.getNodeDefinitionId(),
            "b",
            UUID.randomUUID(),
            1,
            "root",
            "B",
            null,
            null,
            JsonNodeFactory.instance.objectNode(),
            revisionId,
            now.plusSeconds(1));
    when(events.findById(eventId)).thenReturn(Optional.of(event));
    when(versions.findById(versionId)).thenReturn(Optional.of(version));
    when(nodeDefinitions.findAllByWorkflowVersionIdOrderByNodeKeyAsc(versionId))
        .thenReturn(List.of());
    when(edgeDefinitions.findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(versionId))
        .thenReturn(List.of());
    when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
    when(nodes.findAllByEventIdOrderByCreatedAtAsc(eventId)).thenReturn(List.of(first, repeated));
    when(tasks.findAllByNodeExecutionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    when(participants.findAllByEventIdOrderByResolvedAtAsc(eventId)).thenReturn(List.of());
    when(routes.findAllByEventIdOrderByDecidedAtAsc(eventId)).thenReturn(List.of());
    when(actors.requireActor())
        .thenReturn(new ActorContext(actor, "actor", Set.of(RoleKey.USER), Set.of()));
    when(contexts.build(eventId)).thenReturn(context);
    var masked = JsonNodeFactory.instance.objectNode();
    masked.put("secret", "***");
    when(context.maskedValue()).thenReturn(masked);
    EventMonitoringService service =
        new EventMonitoringService(
            events,
            versions,
            nodeDefinitions,
            edgeDefinitions,
            nodes,
            tasks,
            participants,
            assignments,
            routes,
            tickets,
            contexts,
            actors);
    var view = service.get(eventId);
    assertThat(view.workflowVersion().id()).isEqualTo(versionId);
    assertThat(view.workflowVersion().versionNo()).isEqualTo(7);
    assertThat(view.workflowVersion().checksum()).isEqualTo("exact-checksum");
    assertThat(view.graph().nodes()).isEmpty();
    assertThat(view.nodeExecutions()).hasSize(2);
    assertThat(view.nodeExecutions())
        .extracting(EventMonitoringService.NodeOccurrence::iteration)
        .containsExactly(0, 1);
    assertThat(view.nodeExecutions().get(1).item()).isEqualTo("B");
    assertThat(view.maskedContext()).isEqualTo(masked);
    verify(context, never()).value();
  }
}
