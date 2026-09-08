package com.fpt.workflow.integration.repository;

import com.fpt.workflow.integration.domain.IntegrationAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationAttemptRepository extends JpaRepository<IntegrationAttempt, UUID> {

  List<IntegrationAttempt> findAllByIntegrationExecutionIdOrderByAttemptNumberAsc(
      UUID integrationExecutionId);

  Optional<IntegrationAttempt> findByIntegrationExecutionIdAndAttemptNumber(
      UUID integrationExecutionId, int attemptNumber);
}
