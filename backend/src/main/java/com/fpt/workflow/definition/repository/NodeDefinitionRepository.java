package com.fpt.workflow.definition.repository;

import com.fpt.workflow.definition.domain.NodeDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeDefinitionRepository extends JpaRepository<NodeDefinition, UUID> {

  Optional<NodeDefinition> findByWorkflowVersionIdAndNodeKey(
      UUID workflowVersionId, String nodeKey);

  boolean existsByWorkflowVersionIdAndNodeKey(UUID workflowVersionId, String nodeKey);

  List<NodeDefinition> findAllByWorkflowVersionIdOrderByNodeKeyAsc(UUID workflowVersionId);
}
