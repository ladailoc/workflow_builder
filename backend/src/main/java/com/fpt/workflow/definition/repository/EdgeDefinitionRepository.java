package com.fpt.workflow.definition.repository;

import com.fpt.workflow.definition.domain.EdgeDefinition;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EdgeDefinitionRepository extends JpaRepository<EdgeDefinition, UUID> {

  List<EdgeDefinition> findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(UUID workflowVersionId);

  List<EdgeDefinition>
      findAllByWorkflowVersionIdAndSourceNodeIdAndSourcePortOrderByPriorityAscIdAsc(
          UUID workflowVersionId, UUID sourceNodeId, String sourcePort);

  boolean existsBySourceNodeIdOrTargetNodeId(UUID sourceNodeId, UUID targetNodeId);
}
