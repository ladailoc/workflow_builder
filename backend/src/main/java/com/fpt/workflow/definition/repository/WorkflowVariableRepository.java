package com.fpt.workflow.definition.repository;

import com.fpt.workflow.definition.domain.WorkflowVariable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVariableRepository extends JpaRepository<WorkflowVariable, UUID> {

  Optional<WorkflowVariable> findByWorkflowVersionIdAndKey(UUID workflowVersionId, String key);

  boolean existsByWorkflowVersionIdAndKey(UUID workflowVersionId, String key);

  List<WorkflowVariable> findAllByWorkflowVersionIdOrderByKeyAsc(UUID workflowVersionId);
}
