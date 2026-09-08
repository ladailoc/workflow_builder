package com.fpt.workflow.ticket.repository;

import com.fpt.workflow.ticket.domain.Ticket;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {}
