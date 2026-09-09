package com.fpt.workflow.integration.repository;

import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationExecutionRepository extends JpaRepository<IntegrationExecution, UUID> {

  Optional<IntegrationExecution> findByNodeExecutionId(UUID nodeExecutionId);

  Optional<IntegrationExecution> findByIdempotencyKey(String idempotencyKey);

  Optional<IntegrationExecution> findByCallbackCorrelationId(String callbackCorrelationId);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select execution from IntegrationExecution execution where execution.id = :id")
  Optional<IntegrationExecution> findByIdForUpdate(@Param("id") UUID id);

  List<IntegrationExecution> findAllByEventIdOrderByCreatedAtAsc(UUID eventId);

  List<IntegrationExecution> findAllByStatusOrderByUpdatedAtAsc(IntegrationExecutionStatus status);
}
