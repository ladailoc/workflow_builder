package com.fpt.workflow.rework.repository;

import com.fpt.workflow.rework.domain.RevisionRequest;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RevisionRequestRepository extends JpaRepository<RevisionRequest, UUID> {
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RevisionRequest r where r.id = :id")
  Optional<RevisionRequest> findByIdForUpdate(@Param("id") UUID id);

  List<RevisionRequest> findAllByEventIdOrderByCreatedAtAsc(UUID eventId);
}
