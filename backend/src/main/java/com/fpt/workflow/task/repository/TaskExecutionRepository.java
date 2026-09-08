package com.fpt.workflow.task.repository;

import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.task.domain.TaskExecution;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskExecutionRepository extends JpaRepository<TaskExecution, UUID> {

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select task from TaskExecution task where task.id = :id")
  java.util.Optional<TaskExecution> findByIdForUpdate(@Param("id") UUID id);

  List<TaskExecution> findAllByNodeExecutionIdOrderByCreatedAtAsc(UUID nodeExecutionId);

  List<TaskExecution> findAllByAssigneeIdAndStatusOrderByDueAtAsc(
      UUID assigneeId, TaskStatus status);
}
