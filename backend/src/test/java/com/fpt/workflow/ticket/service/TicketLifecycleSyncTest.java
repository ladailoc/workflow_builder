package com.fpt.workflow.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TicketLifecycleSyncTest {

  private TicketRepository ticketRepository;
  private TicketLifecycleSyncService syncService;
  private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

  @BeforeEach
  void setUp() {
    ticketRepository = mock(TicketRepository.class);
    syncService = new TicketLifecycleSyncService(ticketRepository);
  }

  @Test
  void syncsTicketToInProgressWhenEventIsRunningOrWaiting() {
    UUID ticketId = UUID.randomUUID();
    Ticket ticket = createSubmittedTicket(ticketId);
    when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

    syncService.syncTicketStatus(ticketId, EventStatus.RUNNING, null, NOW.plusSeconds(2));

    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    verify(ticketRepository).saveAndFlush(ticket);
  }

  @Test
  void syncsTicketToCompletedWhenEventCompletedWithSuccess() {
    UUID ticketId = UUID.randomUUID();
    Ticket ticket = createSubmittedTicket(ticketId);
    ticket.markInProgress(NOW.plusSeconds(2));
    when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

    syncService.syncTicketStatus(ticketId, EventStatus.COMPLETED, "APPROVED", NOW.plusSeconds(3));

    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.COMPLETED);
    assertThat(ticket.getCompletedAt()).isEqualTo(NOW.plusSeconds(3));
    verify(ticketRepository).saveAndFlush(ticket);
  }

  @Test
  void syncsTicketToRejectedWhenEventCompletedWithRejection() {
    UUID ticketId = UUID.randomUUID();
    Ticket ticket = createSubmittedTicket(ticketId);
    ticket.markInProgress(NOW.plusSeconds(2));
    when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

    syncService.syncTicketStatus(ticketId, EventStatus.COMPLETED, "REJECTED", NOW.plusSeconds(3));

    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.REJECTED);
    assertThat(ticket.getCompletedAt()).isEqualTo(NOW.plusSeconds(3));
    verify(ticketRepository).saveAndFlush(ticket);
  }

  @Test
  void syncsTicketToRejectedWhenEventFails() {
    UUID ticketId = UUID.randomUUID();
    Ticket ticket = createSubmittedTicket(ticketId);
    ticket.markInProgress(NOW.plusSeconds(2));
    when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

    syncService.syncTicketStatus(ticketId, EventStatus.FAILED, null, NOW.plusSeconds(3));

    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.REJECTED);
    verify(ticketRepository).saveAndFlush(ticket);
  }

  @Test
  void syncsTicketToCancelledWhenEventCancelledOrTerminated() {
    UUID ticketId = UUID.randomUUID();
    Ticket ticket = createSubmittedTicket(ticketId);
    ticket.markInProgress(NOW.plusSeconds(2));
    when(ticketRepository.findByIdForUpdate(ticketId)).thenReturn(Optional.of(ticket));

    syncService.syncTicketStatus(ticketId, EventStatus.CANCELLED, "CANCELLED", NOW.plusSeconds(4));

    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CANCELLED);
    assertThat(ticket.getCompletedAt()).isEqualTo(NOW.plusSeconds(4));
    verify(ticketRepository).saveAndFlush(ticket);
  }

  private Ticket createSubmittedTicket(UUID ticketId) {
    Ticket ticket =
        Ticket.createDraft(
            ticketId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            JsonNodeFactory.instance.objectNode().put("key", "val"),
            NOW);
    ticket.submit(UUID.randomUUID(), 1, JsonNodeFactory.instance.objectNode(), NOW.plusSeconds(1));
    return ticket;
  }
}
