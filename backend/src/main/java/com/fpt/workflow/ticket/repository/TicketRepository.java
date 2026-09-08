package com.fpt.workflow.ticket.repository;

import com.fpt.workflow.ticket.domain.Ticket;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select ticket from Ticket ticket where ticket.id = :id")
  Optional<Ticket> findByIdForUpdate(@Param("id") UUID id);
}
