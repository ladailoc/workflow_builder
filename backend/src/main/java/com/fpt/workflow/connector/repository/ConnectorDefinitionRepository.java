package com.fpt.workflow.connector.repository;

import com.fpt.workflow.connector.domain.ConnectorDefinition;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorDefinitionRepository extends JpaRepository<ConnectorDefinition, UUID> {
  Optional<ConnectorDefinition> findByKey(String key);

  boolean existsByKey(String key);
}
