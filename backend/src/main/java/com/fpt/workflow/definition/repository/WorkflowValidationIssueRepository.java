package com.fpt.workflow.definition.repository;

import com.fpt.workflow.definition.domain.WorkflowValidationIssue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowValidationIssueRepository
    extends JpaRepository<WorkflowValidationIssue, UUID> {

  List<WorkflowValidationIssue> findAllByValidationRunIdOrderBySeverityAscRuleCodeAsc(
      UUID validationRunId);
}
