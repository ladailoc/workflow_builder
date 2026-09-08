package com.fpt.workflow.task.aggregation;

import static org.assertj.core.api.Assertions.*;

import com.fpt.workflow.runtime.multiinstance.domain.CompletionPolicy;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TaskAggregationPolicyTest {
  @Test
  void allApproveAndRejectBehaviors() {
    TaskAggregationState all =
        decision(3, DecisionAggregationPolicy.ALL_APPROVE, null, RejectBehavior.WAIT_ALL);
    all.recordDecision(true);
    all.recordDecision(false);
    assertThat(all.getOutcome()).isNull();
    all.recordDecision(true);
    assertThat(all.getOutcome()).isEqualTo(AggregationOutcome.REJECTED);
    TaskAggregationState fast =
        decision(3, DecisionAggregationPolicy.ALL_APPROVE, null, RejectBehavior.FAIL_FAST);
    fast.recordDecision(false);
    assertThat(fast.getOutcome()).isEqualTo(AggregationOutcome.REJECTED);
  }

  @Test
  void anyAndMajorityBoundaries() {
    TaskAggregationState any =
        decision(5, DecisionAggregationPolicy.ANY_APPROVE, null, RejectBehavior.WAIT_ALL);
    any.recordDecision(true);
    assertThat(any.getOutcome()).isEqualTo(AggregationOutcome.APPROVED);
    TaskAggregationState majority =
        decision(4, DecisionAggregationPolicy.MAJORITY_APPROVE, null, RejectBehavior.WAIT_ALL);
    majority.recordDecision(true);
    majority.recordDecision(true);
    assertThat(majority.getOutcome()).isNull();
    majority.recordDecision(true);
    assertThat(majority.getOutcome()).isEqualTo(AggregationOutcome.APPROVED);
  }

  @Test
  void nOfMAndPercentageBoundaries() {
    TaskAggregationState n =
        decision(5, DecisionAggregationPolicy.N_OF_M_APPROVE, 3, RejectBehavior.WAIT_ALL);
    n.recordDecision(true);
    n.recordDecision(true);
    assertThat(n.getOutcome()).isNull();
    n.recordDecision(true);
    assertThat(n.getOutcome()).isEqualTo(AggregationOutcome.APPROVED);
    TaskAggregationState pct =
        decision(3, DecisionAggregationPolicy.PERCENTAGE_APPROVE, 67, RejectBehavior.WAIT_ALL);
    pct.recordDecision(true);
    pct.recordDecision(true);
    assertThat(pct.getOutcome()).isNull();
    pct.recordDecision(true);
    assertThat(pct.getOutcome()).isEqualTo(AggregationOutcome.APPROVED);
  }

  @Test
  void allNonDecisionCompletionPolicies() {
    for (CompletionPolicy policy : CompletionPolicy.values()) {
      Integer threshold =
          switch (policy) {
            case N_OF_M -> 2;
            case PERCENTAGE -> 50;
            default -> null;
          };
      TaskAggregationState state =
          TaskAggregationState.create(
              UUID.randomUUID(),
              4,
              new TaskAggregationPolicy(
                  null,
                  policy,
                  threshold,
                  RejectBehavior.WAIT_ALL,
                  RemainingTaskBehavior.CANCEL_REMAINING));
      int required =
          switch (policy) {
            case ALL -> 4;
            case ANY -> 1;
            case N_OF_M, PERCENTAGE -> 2;
          };
      for (int i = 1; i < required; i++) {
        state.recordCompletion();
        assertThat(state.getOutcome()).isNull();
      }
      state.recordCompletion();
      assertThat(state.getOutcome()).isEqualTo(AggregationOutcome.COMPLETED);
    }
  }

  @Test
  void rejectsIncoherentThresholds() {
    assertThatThrownBy(
            () -> decision(2, DecisionAggregationPolicy.N_OF_M_APPROVE, 3, RejectBehavior.WAIT_ALL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TaskAggregationPolicy(
                    DecisionAggregationPolicy.PERCENTAGE_APPROVE,
                    null,
                    101,
                    RejectBehavior.WAIT_ALL,
                    RemainingTaskBehavior.KEEP_ACTIVE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TaskAggregationPolicy(
                    DecisionAggregationPolicy.ANY_APPROVE,
                    null,
                    1,
                    RejectBehavior.WAIT_ALL,
                    RemainingTaskBehavior.KEEP_ACTIVE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void concurrentThresholdClaimHasSingleWinner() throws Exception {
    TaskAggregationState state =
        decision(2, DecisionAggregationPolicy.ANY_APPROVE, null, RejectBehavior.WAIT_ALL);
    state.recordDecision(true);
    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<Boolean>> claims = new ArrayList<>();
      for (int i = 0; i < 20; i++) claims.add(executor.submit(state::claimCompletion));
      long winners = 0;
      for (Future<Boolean> claim : claims) if (claim.get()) winners++;
      assertThat(winners).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  private TaskAggregationState decision(
      int total, DecisionAggregationPolicy policy, Integer threshold, RejectBehavior reject) {
    return TaskAggregationState.create(
        UUID.randomUUID(),
        total,
        new TaskAggregationPolicy(
            policy, null, threshold, reject, RemainingTaskBehavior.CANCEL_REMAINING));
  }
}
