package com.fpt.workflow.task.repository;

import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.task.domain.TaskExecution;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskExecutionRepository extends JpaRepository<TaskExecution, UUID> {

  List<TaskExecution> findAllByNodeExecutionIdOrderByCreatedAtAsc(UUID nodeExecutionId);

  List<TaskExecution> findAllByAssigneeIdAndStatusOrderByDueAtAsc(
      UUID assigneeId, TaskStatus status);
}
