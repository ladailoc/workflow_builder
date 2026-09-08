package com.fpt.workflow.runtime.multiinstance.repository;

import com.fpt.workflow.runtime.multiinstance.domain.MultiInstanceState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MultiInstanceStateRepository extends JpaRepository<MultiInstanceState, UUID> {

  Optional<MultiInstanceState> findByNodeExecutionId(UUID nodeExecutionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM MultiInstanceState m WHERE m.id = :id")
  Optional<MultiInstanceState> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM MultiInstanceState m WHERE m.nodeExecutionId = :nodeExecutionId")
  Optional<MultiInstanceState> findByNodeExecutionIdForUpdate(
      @Param("nodeExecutionId") UUID nodeExecutionId);
}
