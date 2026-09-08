package com.fpt.workflow.task.repository;

import com.fpt.workflow.task.domain.TaskCandidate;
import com.fpt.workflow.task.domain.TaskCandidateId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCandidateRepository extends JpaRepository<TaskCandidate, TaskCandidateId> {

  List<TaskCandidate> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

  List<TaskCandidate> findAllByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
