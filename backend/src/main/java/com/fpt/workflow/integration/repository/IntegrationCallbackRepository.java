package com.fpt.workflow.integration.repository;

import com.fpt.workflow.integration.domain.IntegrationCallback;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationCallbackRepository extends JpaRepository<IntegrationCallback, UUID> {

  Optional<IntegrationCallback> findByConnectorKeyAndExternalEventId(
      String connectorKey, String externalEventId);

  Optional<IntegrationCallback> findByCallbackCorrelationId(String callbackCorrelationId);

  List<IntegrationCallback> findAllByCallbackCorrelationIdOrderByReceivedAtAsc(
      String callbackCorrelationId);

  List<IntegrationCallback> findAllByIntegrationExecutionIdOrderByReceivedAtAsc(
      UUID integrationExecutionId);
}
