package com.fpt.workflow.runtime.repository;

import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NodeExecutionRepository extends JpaRepository<NodeExecution, UUID> {

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select execution from NodeExecution execution where execution.id = :id")
  Optional<NodeExecution> findByIdForUpdate(@Param("id") UUID id);

  Optional<NodeExecution> findByActivationKey(String activationKey);

  List<NodeExecution> findAllByEventIdOrderByCreatedAtAsc(UUID eventId);

  List<NodeExecution> findAllByEventIdAndNodeDefinitionIdOrderByCreatedAtAsc(
      UUID eventId, UUID nodeDefinitionId);

  List<NodeExecution> findAllByStatusOrderByCreatedAtAsc(NodeExecutionStatus status);
}
