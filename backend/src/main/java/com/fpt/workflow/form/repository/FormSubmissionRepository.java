package com.fpt.workflow.form.repository;
import com.fpt.workflow.form.domain.FormSubmission;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormSubmissionRepository extends JpaRepository<FormSubmission,UUID>{}
