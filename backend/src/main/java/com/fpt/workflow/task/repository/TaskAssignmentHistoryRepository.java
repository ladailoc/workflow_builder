package com.fpt.workflow.task.repository;

import com.fpt.workflow.task.domain.TaskAssignmentHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskAssignmentHistoryRepository
    extends JpaRepository<TaskAssignmentHistory, UUID> {

  List<TaskAssignmentHistory> findAllByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
