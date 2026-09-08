package com.fpt.workflow.task.service;

import com.fpt.workflow.runtime.lifecycle.ActiveTaskCancellationPort;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Propagates cancellation to active tasks of a cancelled node execution while preserving completed
 * tasks.
 */
@Service
@Primary
public class DefaultActiveTaskCancellationPort implements ActiveTaskCancellationPort {

  private final TaskExecutionRepository taskRepository;
  private final TaskSlaActivationPort slaActivationService;

  @Autowired
  public DefaultActiveTaskCancellationPort(
      TaskExecutionRepository taskRepository, TaskSlaActivationPort slaActivationService) {
    this.taskRepository = taskRepository;
    this.slaActivationService = slaActivationService;
  }

  public DefaultActiveTaskCancellationPort(TaskExecutionRepository taskRepository) {
    this(taskRepository, null);
  }

  @Override
  public void cancelActiveTasks(UUID nodeExecutionId, Instant cancelledAt) {
    List<TaskExecution> tasks =
        taskRepository.findAllByNodeExecutionIdOrderByCreatedAtAsc(nodeExecutionId);
    for (TaskExecution task : tasks) {
      if (task.getStatus() != TaskStatus.COMPLETED
          && task.getStatus() != TaskStatus.CANCELLED
          && task.getStatus() != TaskStatus.EXPIRED) {
        task.cancel(new BusinessOutcome("CANCELLED"), cancelledAt);
        taskRepository.save(task);
        if (slaActivationService != null) {
          slaActivationService.cancel(task.getId(), cancelledAt);
        }
      }
    }
  }
}
