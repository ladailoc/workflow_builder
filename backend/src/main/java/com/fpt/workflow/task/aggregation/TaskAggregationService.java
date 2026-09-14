package com.fpt.workflow.task.aggregation;

import com.fpt.workflow.shared.time.PlatformClock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskAggregationService {
  private final TaskAggregationStateRepository states;
  private final TaskAggregationVoteRepository votes;
  private final PlatformClock clock;

  public TaskAggregationService(
      TaskAggregationStateRepository states,
      TaskAggregationVoteRepository votes,
      PlatformClock clock) {
    this.states = states;
    this.votes = votes;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public boolean hasAggregation(UUID nodeId) {
    return states.existsById(nodeId);
  }

  @Transactional(readOnly = true)
  public Optional<TaskAggregationState> findState(UUID nodeId) {
    return states.findById(nodeId);
  }

  @Transactional
  public AggregationResult recordTaskDecision(
      UUID nodeId, UUID taskId, com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome outcome) {
    TaskAggregationState state =
        states
            .findById(nodeId)
            .orElseThrow(() -> new IllegalArgumentException("Aggregation state not found: " + nodeId));
    if (state.getDecisionPolicy() != null) {
      boolean approved = com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome.APPROVED.equals(outcome);
      return recordDecision(nodeId, taskId, approved);
    } else {
      return recordCompletion(nodeId, taskId);
    }
  }

  @Transactional
  public AggregationResult recordDecision(UUID nodeId, UUID taskId, boolean approved) {
    return record(nodeId, taskId, approved ? "APPROVED" : "REJECTED", true, approved);
  }

  @Transactional
  public AggregationResult recordCompletion(UUID nodeId, UUID taskId) {
    return record(nodeId, taskId, "COMPLETED", false, false);
  }

  private AggregationResult record(
      UUID nodeId, UUID taskId, String outcome, boolean decision, boolean approved) {
    Optional<TaskAggregationVote> replay = votes.findById(taskId);
    if (replay.isPresent()) {
      if (!replay.orElseThrow().getOutcome().equals(outcome))
        throw new IllegalStateException("Task aggregation outcome conflict");
      TaskAggregationState s = states.findById(nodeId).orElseThrow();
      return result(s, false, true);
    }
    TaskAggregationState state = states.findByIdForUpdate(nodeId).orElseThrow();
    // Re-check after serializing on aggregate row, closing the race between the optimistic pre-read
    // and insert.
    replay = votes.findById(taskId);
    if (replay.isPresent()) return result(state, false, true);
    if (decision) state.recordDecision(approved);
    else state.recordCompletion();
    votes.saveAndFlush(TaskAggregationVote.record(taskId, nodeId, outcome, clock.now()));
    boolean first = state.claimCompletion();
    states.saveAndFlush(state);
    return result(state, first, false);
  }

  private AggregationResult result(TaskAggregationState s, boolean first, boolean replay) {
    return new AggregationResult(s.getOutcome(), first, replay, s.getRemainingTaskBehavior());
  }

  public record AggregationResult(
      AggregationOutcome outcome,
      boolean firstThresholdCrossing,
      boolean replay,
      RemainingTaskBehavior remainingTaskBehavior) {}
}
