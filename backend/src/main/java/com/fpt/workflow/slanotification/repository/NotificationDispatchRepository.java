package com.fpt.workflow.slanotification.repository;

import com.fpt.workflow.slanotification.domain.NotificationDispatch;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationDispatchRepository extends JpaRepository<NotificationDispatch, UUID> {
  Optional<NotificationDispatch> findByDedupKey(String dedupKey);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select dispatch from NotificationDispatch dispatch where dispatch.id=:id")
  Optional<NotificationDispatch> findByIdForUpdate(@Param("id") UUID id);
}
