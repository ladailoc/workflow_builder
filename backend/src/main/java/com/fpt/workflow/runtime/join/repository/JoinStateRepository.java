package com.fpt.workflow.runtime.join.repository;

import com.fpt.workflow.runtime.join.domain.JoinState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JoinStateRepository extends JpaRepository<JoinState, UUID> {

  Optional<JoinState> findByEventIdAndNodeDefinitionIdAndJoinScopeId(
      UUID eventId, UUID nodeDefinitionId, UUID joinScopeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT s FROM JoinState s WHERE s.eventId = :eventId AND s.nodeDefinitionId = :nodeDefinitionId AND s.joinScopeId = :joinScopeId")
  Optional<JoinState> findByScopeForUpdate(
      @Param("eventId") UUID eventId,
      @Param("nodeDefinitionId") UUID nodeDefinitionId,
      @Param("joinScopeId") UUID joinScopeId);

  Optional<JoinState> findByJoinNodeExecutionId(UUID joinNodeExecutionId);
}
