package com.fpt.workflow.slanotification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.resolver.participant.*;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.slanotification.domain.NotificationDispatch;
import com.fpt.workflow.slanotification.service.*;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationServiceTest {
  @Test
  void resolvesRecipientThroughRegistryAndMasksSensitivePayload() {
    EventRepository events = mock(EventRepository.class);
    TicketRepository tickets = mock(TicketRepository.class);
    ParticipantResolverRegistry resolvers = mock(ParticipantResolverRegistry.class);
    NotificationTransactions tx = mock(NotificationTransactions.class);
    PlatformClock clock = mock(PlatformClock.class);
    Event event = mock(Event.class);
    Ticket ticket = mock(Ticket.class);
    UUID eventId = UUID.randomUUID(),
        ticketId = UUID.randomUUID(),
        creator = UUID.randomUUID(),
        recipient = UUID.randomUUID();
    when(events.findById(eventId)).thenReturn(Optional.of(event));
    when(event.getId()).thenReturn(eventId);
    when(event.getTicketId()).thenReturn(ticketId);
    when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
    when(ticket.getCreatorId()).thenReturn(creator);
    when(clock.now()).thenReturn(Instant.parse("2026-09-08T00:00:00Z"));
    when(resolvers.resolve(anyString(), any())).thenReturn(recipient);
    NotificationDispatch expected = mock(NotificationDispatch.class);
    when(tx.create(
            any(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString(),
            anyInt(),
            anyBoolean()))
        .thenReturn(expected);
    var participant = JsonNodeFactory.instance.objectNode().put("type", "CREATOR");
    var template = JsonNodeFactory.instance.objectNode().put("templateKey", "PURCHASE_APPROVED");
    var payload =
        JsonNodeFactory.instance
            .objectNode()
            .put("amount", 150_000_000)
            .put("accessToken", "must-not-leak");
    NotificationService service = new NotificationService(events, tickets, resolvers, tx, clock);
    assertThat(
            service.dispatch(
                new NotificationRequest(
                    eventId,
                    null,
                    null,
                    "EMAIL",
                    participant,
                    null,
                    null,
                    template,
                    payload,
                    "dedup",
                    3,
                    false)))
        .isSameAs(expected);
    ArgumentCaptor<JsonNode> snapshot = ArgumentCaptor.forClass(JsonNode.class);
    ArgumentCaptor<JsonNode> safePayload = ArgumentCaptor.forClass(JsonNode.class);
    verify(tx)
        .create(
            eq(eventId),
            isNull(),
            isNull(),
            eq("EMAIL"),
            eq(recipient),
            snapshot.capture(),
            eq(template),
            safePayload.capture(),
            eq("dedup"),
            eq(3),
            eq(false));
    assertThat(snapshot.getValue().path("resolverType").asText()).isEqualTo("CREATOR");
    assertThat(safePayload.getValue().path("accessToken").asText()).isEqualTo("***REDACTED***");
  }

  @Test
  void deliveryFailureIsRetryableWithoutEventMutationAndLateTerminalJobIsNoop() {
    NotificationTransactions tx = mock(NotificationTransactions.class);
    NotificationChannelRegistry channels = mock(NotificationChannelRegistry.class);
    NotificationChannel channel = mock(NotificationChannel.class);
    NotificationDispatch dispatch = mock(NotificationDispatch.class);
    WorkflowJobFixture fixture = new WorkflowJobFixture();
    when(tx.begin(fixture.id)).thenReturn(Optional.of(dispatch));
    when(dispatch.getId()).thenReturn(fixture.id);
    when(dispatch.getChannel()).thenReturn("EMAIL");
    when(channels.require("EMAIL")).thenReturn(channel);
    doThrow(new IllegalStateException("mail down")).when(channel).send(dispatch);
    NotificationJobHandler handler = new NotificationJobHandler(tx, channels);
    var result = handler.execute(fixture.job);
    assertThat(result).isInstanceOf(com.fpt.workflow.operations.job.JobExecutionResult.Retry.class);
    verify(tx).failed(eq(dispatch.getId()), any(), eq(false));
    when(tx.begin(fixture.id)).thenReturn(Optional.empty());
    assertThat(handler.execute(fixture.job))
        .isInstanceOf(com.fpt.workflow.operations.job.JobExecutionResult.Success.class);
  }

  private static final class WorkflowJobFixture {
    final UUID id = UUID.randomUUID();
    final com.fpt.workflow.operations.job.WorkflowJob job =
        mock(com.fpt.workflow.operations.job.WorkflowJob.class);

    WorkflowJobFixture() {
      when(job.getAggregateId()).thenReturn(id);
      when(job.getPayloadJson())
          .thenReturn(JsonNodeFactory.instance.objectNode().put("dispatchId", id.toString()));
      when(job.getAttempts()).thenReturn(1);
      when(job.getMaxAttempts()).thenReturn(3);
    }
  }
}
