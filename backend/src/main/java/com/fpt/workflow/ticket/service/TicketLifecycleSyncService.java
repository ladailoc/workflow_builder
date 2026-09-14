package com.fpt.workflow.ticket.service;

import com.fpt.workflow.runtime.lifecycle.TicketLifecycleSyncPort;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synchronizes Ticket lifecycle state from Event execution lifecycle.
 */
@Service("ticketLifecycleSyncService")
@Primary
public class TicketLifecycleSyncService implements TicketLifecycleSyncPort {

  private final TicketRepository ticketRepository;

  public TicketLifecycleSyncService(TicketRepository ticketRepository) {
    this.ticketRepository = ticketRepository;
  }

  @Override
  @Transactional
  public void syncTicketStatus(
      UUID ticketId, EventStatus eventStatus, String outcome, Instant timestamp) {
    if (ticketId == null || eventStatus == null) {
      return;
    }
    Optional<Ticket> ticketOpt = ticketRepository.findByIdForUpdate(ticketId);
    if (ticketOpt.isEmpty()) {
      return;
    }
    Ticket ticket = ticketOpt.get();
    Instant at = timestamp != null ? timestamp : Instant.now();

    switch (eventStatus) {
      case RUNNING, WAITING -> {
        if (ticket.getStatus() == TicketStatus.SUBMITTED) {
          ticket.markInProgress(at);
          ticketRepository.saveAndFlush(ticket);
        }
      }
      case COMPLETED -> {
        if (ticket.getStatus() == TicketStatus.SUBMITTED
            || ticket.getStatus() == TicketStatus.IN_PROGRESS) {
          if (outcome != null && outcome.toUpperCase(Locale.ROOT).contains("REJECT")) {
            ticket.reject(at);
          } else {
            ticket.complete(at);
          }
          ticketRepository.saveAndFlush(ticket);
        }
      }
      case FAILED -> {
        if (ticket.getStatus() == TicketStatus.SUBMITTED
            || ticket.getStatus() == TicketStatus.IN_PROGRESS) {
          ticket.reject(at);
          ticketRepository.saveAndFlush(ticket);
        }
      }
      case CANCELLED, TERMINATED -> {
        if (ticket.getStatus() != TicketStatus.CANCELLED
            && ticket.getStatus() != TicketStatus.COMPLETED
            && ticket.getStatus() != TicketStatus.REJECTED) {
          ticket.cancel(at);
          ticketRepository.saveAndFlush(ticket);
        }
      }
      default -> {
        // CREATED or other - do nothing
      }
    }
  }
}
