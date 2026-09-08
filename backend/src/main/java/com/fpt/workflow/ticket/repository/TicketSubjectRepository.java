package com.fpt.workflow.ticket.repository;

import com.fpt.workflow.ticket.domain.TicketSubject;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketSubjectRepository extends JpaRepository<TicketSubject, UUID> {

  List<TicketSubject> findAllByTicketIdOrderByCreatedAtAsc(UUID ticketId);

  void deleteAllByTicketId(UUID ticketId);
}
