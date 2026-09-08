package com.fpt.workflow.form.repository;

import com.fpt.workflow.form.domain.WorkflowForm;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowFormRepository extends JpaRepository<WorkflowForm, UUID> {

  Optional<WorkflowForm> findByWorkflowVersionIdAndFormKey(UUID workflowVersionId, String formKey);

  boolean existsByWorkflowVersionIdAndFormKey(UUID workflowVersionId, String formKey);

  List<WorkflowForm> findAllByWorkflowVersionIdOrderByFormKeyAsc(UUID workflowVersionId);
}
