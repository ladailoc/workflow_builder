package com.fpt.workflow.operations.job;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowJobRepository extends JpaRepository<WorkflowJob, UUID> {
  Optional<WorkflowJob> findByDedupKey(String dedupKey);

  List<WorkflowJob> findAllByStatusOrderByUpdatedAtAsc(WorkflowJobStatus status);
}
