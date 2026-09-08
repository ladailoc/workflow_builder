package com.fpt.workflow.ticket.repository;

import com.fpt.workflow.ticket.domain.TicketRevision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRevisionRepository extends JpaRepository<TicketRevision, UUID> {

  List<TicketRevision> findAllByTicketIdOrderByRevisionNoAsc(UUID ticketId);
}
