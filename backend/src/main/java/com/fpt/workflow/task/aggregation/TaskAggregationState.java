package com.fpt.workflow.task.aggregation;

import com.fpt.workflow.runtime.multiinstance.domain.CompletionPolicy;
import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "task_aggregation_states")
public class TaskAggregationState {
  @Id
  @Column(name = "node_execution_id")
  private UUID nodeExecutionId;

  @Column(name = "total_tasks", nullable = false)
  private int totalTasks;

  @Enumerated(EnumType.STRING)
  @Column(name = "decision_policy")
  private DecisionAggregationPolicy decisionPolicy;

  @Enumerated(EnumType.STRING)
  @Column(name = "completion_policy")
  private CompletionPolicy completionPolicy;

  @Column private Integer threshold;

  @Enumerated(EnumType.STRING)
  @Column(name = "reject_behavior", nullable = false)
  private RejectBehavior rejectBehavior;

  @Enumerated(EnumType.STRING)
  @Column(name = "remaining_task_behavior", nullable = false)
  private RemainingTaskBehavior remainingTaskBehavior;

  @Column(name = "completed_tasks", nullable = false)
  private int completedTasks;

  @Column(nullable = false)
  private int approvals;

  @Column(nullable = false)
  private int rejections;

  @Enumerated(EnumType.STRING)
  @Column
  private AggregationOutcome outcome;

  @Column(name = "completion_claimed", nullable = false)
  private boolean completionClaimed;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected TaskAggregationState() {}

  public static TaskAggregationState create(
      UUID nodeExecutionId, int total, TaskAggregationPolicy policy) {
    policy.validateForTotal(total);
    TaskAggregationState state = new TaskAggregationState();
    state.nodeExecutionId = Objects.requireNonNull(nodeExecutionId);
    state.totalTasks = total;
    state.decisionPolicy = policy.decisionPolicy();
    state.completionPolicy = policy.completionPolicy();
    state.threshold = policy.threshold();
    state.rejectBehavior = policy.rejectBehavior();
    state.remainingTaskBehavior = policy.remainingTaskBehavior();
    return state;
  }

  public void recordDecision(boolean approved) {
    requireOpen();
    completedTasks++;
    if (approved) approvals++;
    else rejections++;
    evaluateDecision();
  }

  public void recordCompletion() {
    requireOpen();
    completedTasks++;
    if (completionReached()) outcome = AggregationOutcome.COMPLETED;
  }

  private void requireOpen() {
    if (outcome != null) throw new IllegalStateException("Aggregation is terminal");
  }

  private void evaluateDecision() {
    if (rejections > 0 && rejectBehavior == RejectBehavior.FAIL_FAST) {
      outcome = AggregationOutcome.REJECTED;
      return;
    }
    int required = requiredApprovals();
    if (approvals >= required) {
      outcome = AggregationOutcome.APPROVED;
      return;
    }
    if (completedTasks >= totalTasks) outcome = AggregationOutcome.REJECTED;
  }

  private int requiredApprovals() {
    return switch (decisionPolicy) {
      case ALL_APPROVE -> totalTasks;
      case ANY_APPROVE -> 1;
      case MAJORITY_APPROVE -> totalTasks / 2 + 1;
      case N_OF_M_APPROVE -> threshold;
      case PERCENTAGE_APPROVE -> (int) Math.ceil(totalTasks * (threshold / 100.0));
    };
  }

  private boolean completionReached() {
    return switch (completionPolicy) {
      case ALL -> completedTasks >= totalTasks;
      case ANY -> completedTasks >= 1;
      case N_OF_M -> completedTasks >= threshold;
      case PERCENTAGE -> completedTasks >= (int) Math.ceil(totalTasks * (threshold / 100.0));
    };
  }

  /** Atomic claim ensures only one concurrent threshold crossing completes/routes the parent. */
  public synchronized boolean claimCompletion() {
    if (outcome == null || completionClaimed) return false;
    completionClaimed = true;
    return true;
  }

  public UUID getNodeExecutionId() {
    return nodeExecutionId;
  }

  public int getTotalTasks() {
    return totalTasks;
  }

  public int getCompletedTasks() {
    return completedTasks;
  }

  public int getApprovals() {
    return approvals;
  }

  public int getRejections() {
    return rejections;
  }

  public AggregationOutcome getOutcome() {
    return outcome;
  }

  public boolean isCompletionClaimed() {
    return completionClaimed;
  }

  public RemainingTaskBehavior getRemainingTaskBehavior() {
    return remainingTaskBehavior;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
