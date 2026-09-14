package com.fpt.workflow.ticket.repository;
import com.fpt.workflow.ticket.domain.TicketStateHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TicketStateHistoryRepository extends JpaRepository<TicketStateHistory,UUID>{List<TicketStateHistory> findAllByTicketIdOrderByEnteredAtAsc(UUID ticketId);}
