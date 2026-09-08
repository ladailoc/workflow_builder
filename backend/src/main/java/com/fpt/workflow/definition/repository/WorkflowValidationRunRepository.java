package com.fpt.workflow.definition.repository;

import com.fpt.workflow.definition.domain.WorkflowValidationRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowValidationRunRepository
    extends JpaRepository<WorkflowValidationRun, UUID> {

  List<WorkflowValidationRun> findAllByWorkflowVersionIdOrderByValidatedAtDesc(
      UUID workflowVersionId);
}
