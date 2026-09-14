package com.fpt.workflow.sla.repository;

import com.fpt.workflow.sla.domain.SlaExecution;
import java.time.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SlaExecutionRepository extends JpaRepository<SlaExecution, UUID> {
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from SlaExecution s where s.id=:id")
  Optional<SlaExecution> findByIdForUpdate(@Param("id") UUID id);

  List<SlaExecution> findAllByStatusAndNextActionAtLessThanEqual(String status, Instant at);

  Optional<SlaExecution> findByTaskId(UUID taskId);

  /** P2-18: timeline source — SLA executions of one event, earliest first. */
  @org.springframework.data.jpa.repository.Query(
      "select s from SlaExecution s where s.eventId = :eventId order by s.startedAt asc")
  List<SlaExecution> findAllByEventIdOrderByStartedAtAsc(
      @org.springframework.data.repository.query.Param("eventId") UUID eventId);
}
