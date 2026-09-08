package com.fpt.workflow.task.aggregation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "task_aggregation_votes")
public class TaskAggregationVote {
  @Id
  @Column(name = "task_id")
  private UUID taskId;

  @Column(name = "node_execution_id", nullable = false)
  private UUID nodeExecutionId;

  @Column(nullable = false, length = 32)
  private String outcome;

  @Column(name = "recorded_at", nullable = false, columnDefinition = "timestamptz")
  private Instant recordedAt;

  protected TaskAggregationVote() {}

  public static TaskAggregationVote record(UUID taskId, UUID nodeId, String outcome, Instant at) {
    TaskAggregationVote v = new TaskAggregationVote();
    v.taskId = Objects.requireNonNull(taskId);
    v.nodeExecutionId = Objects.requireNonNull(nodeId);
    if (outcome == null || outcome.isBlank())
      throw new IllegalArgumentException("outcome required");
    v.outcome = outcome;
    v.recordedAt = Objects.requireNonNull(at);
    return v;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public String getOutcome() {
    return outcome;
  }
}
