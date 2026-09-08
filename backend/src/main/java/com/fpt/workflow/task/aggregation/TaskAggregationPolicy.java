package com.fpt.workflow.task.aggregation;

import com.fpt.workflow.runtime.multiinstance.domain.CompletionPolicy;
import java.util.Objects;

/** Exactly one of decisionPolicy/completionPolicy is present. Threshold is N or percentage. */
public record TaskAggregationPolicy(
    DecisionAggregationPolicy decisionPolicy,
    CompletionPolicy completionPolicy,
    Integer threshold,
    RejectBehavior rejectBehavior,
    RemainingTaskBehavior remainingTaskBehavior) {
  public TaskAggregationPolicy {
    if ((decisionPolicy == null) == (completionPolicy == null))
      throw new IllegalArgumentException("Exactly one aggregation policy kind is required");
    rejectBehavior = Objects.requireNonNull(rejectBehavior);
    remainingTaskBehavior = Objects.requireNonNull(remainingTaskBehavior);
    boolean requiresThreshold =
        decisionPolicy == DecisionAggregationPolicy.N_OF_M_APPROVE
            || decisionPolicy == DecisionAggregationPolicy.PERCENTAGE_APPROVE
            || completionPolicy == CompletionPolicy.N_OF_M
            || completionPolicy == CompletionPolicy.PERCENTAGE;
    if (requiresThreshold != (threshold != null))
      throw new IllegalArgumentException("Threshold presence does not match aggregation policy");
    if (threshold != null && threshold <= 0)
      throw new IllegalArgumentException("Threshold must be positive");
    if ((decisionPolicy == DecisionAggregationPolicy.PERCENTAGE_APPROVE
            || completionPolicy == CompletionPolicy.PERCENTAGE)
        && threshold > 100)
      throw new IllegalArgumentException("Percentage threshold must be 1..100");
  }

  public void validateForTotal(int total) {
    if (total <= 0) throw new IllegalArgumentException("totalTasks must be positive");
    if ((decisionPolicy == DecisionAggregationPolicy.N_OF_M_APPROVE
            || completionPolicy == CompletionPolicy.N_OF_M)
        && threshold > total) throw new IllegalArgumentException("N threshold cannot exceed M");
  }
}
