package com.fpt.workflow.integration.repository;

import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationExecutionRepository extends JpaRepository<IntegrationExecution, UUID> {

  Optional<IntegrationExecution> findByNodeExecutionId(UUID nodeExecutionId);

  Optional<IntegrationExecution> findByIdempotencyKey(String idempotencyKey);

  Optional<IntegrationExecution> findByCallbackCorrelationId(String callbackCorrelationId);

  List<IntegrationExecution> findAllByEventIdOrderByCreatedAtAsc(UUID eventId);

  List<IntegrationExecution> findAllByStatusOrderByUpdatedAtAsc(IntegrationExecutionStatus status);
}
