package com.fpt.workflow.connector.repository;

import com.fpt.workflow.connector.domain.ConnectorAction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorActionRepository extends JpaRepository<ConnectorAction, UUID> {
  Optional<ConnectorAction> findByConnectorIdAndActionKey(UUID connectorId, String actionKey);

  List<ConnectorAction> findAllByConnectorIdOrderByActionKeyAsc(UUID connectorId);
}
