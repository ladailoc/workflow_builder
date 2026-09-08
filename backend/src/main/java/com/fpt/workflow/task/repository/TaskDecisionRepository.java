package com.fpt.workflow.task.repository;

import com.fpt.workflow.task.domain.TaskDecision;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskDecisionRepository extends JpaRepository<TaskDecision, UUID> {

  Optional<TaskDecision> findByTaskId(UUID taskId);

  Optional<TaskDecision> findByCommandId(UUID commandId);
}
