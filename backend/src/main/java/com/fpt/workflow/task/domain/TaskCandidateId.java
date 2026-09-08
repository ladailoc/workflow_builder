package com.fpt.workflow.task.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public final class TaskCandidateId implements Serializable {

  private UUID taskId;
  private UUID userId;

  public TaskCandidateId() {}

  public TaskCandidateId(UUID taskId, UUID userId) {
    this.taskId = Objects.requireNonNull(taskId, "taskId");
    this.userId = Objects.requireNonNull(userId, "userId");
  }

  public UUID getTaskId() {
    return taskId;
  }

  public UUID getUserId() {
    return userId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof TaskCandidateId that)) {
      return false;
    }
    return taskId.equals(that.taskId) && userId.equals(that.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(taskId, userId);
  }
}
