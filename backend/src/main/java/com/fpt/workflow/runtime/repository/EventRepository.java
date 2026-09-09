package com.fpt.workflow.runtime.repository;

import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select event from Event event where event.id = :id")
  java.util.Optional<Event> findByIdForUpdate(@Param("id") UUID id);

  List<Event> findAllByTicketIdOrderByStartedAtAsc(UUID ticketId);

  List<Event> findAllByStatusOrderByStartedAtAsc(EventStatus status);
}
