package com.fpt.workflow.runtime.rework;

import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.rework.ReworkPolicy;
import com.fpt.workflow.definition.rework.ReworkScope;
import com.fpt.workflow.runtime.domain.NodeExecution;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Pure deterministic rework scope planner. It never mutates a terminal source occurrence. */
@Component
public final class ReworkRuntimePlanner {

  public boolean isRework(EdgeDefinition edge) {
    return edge.getTransitionType() == TransitionType.REWORK
        || edge.getTransitionType() == TransitionType.RETURN;
  }

  public ReworkPlan plan(UUID eventId, EdgeDefinition edge, NodeExecution source) {
    Objects.requireNonNull(eventId, "eventId");
    if (!isRework(edge)) {
      return new ReworkPlan(
          false,
          null,
          null,
          source.getCycleId(),
          source.getIteration(),
          source.getItemToken(),
          source.getSplitScopeId(),
          source.getJoinScopeId());
    }
    ReworkPolicy policy = ReworkPolicy.fromEdgeConfig(edge.getConfigJson());
    if ((source.getSplitScopeId() != null || source.getJoinScopeId() != null)
        && !policy.allowParallelScopeReset()) {
      throw new IllegalStateException("REWORK_PARALLEL_SCOPE_CROSSING_UNSAFE");
    }
    if (policy.scope() == ReworkScope.CURRENT_ITEM && source.getItemToken() == null) {
      throw new IllegalStateException("REWORK_CURRENT_ITEM_SCOPE_MISSING");
    }
    int nextIteration = source.getIteration() + 1;
    if (nextIteration > policy.maxIterations()) {
      return new ReworkPlan(
          true,
          policy.onExhausted(),
          policy.exhaustionPort(),
          source.getCycleId(),
          source.getIteration(),
          source.getItemToken(),
          source.getSplitScopeId(),
          source.getJoinScopeId());
    }
    UUID nextCycle =
        UUID.nameUUIDFromBytes(
            (eventId + ":" + source.getId() + ":" + edge.getId() + ":" + nextIteration)
                .getBytes(StandardCharsets.UTF_8));
    String itemToken = policy.scope() == ReworkScope.CURRENT_ITEM ? source.getItemToken() : null;
    UUID splitScope = policy.allowParallelScopeReset() ? null : source.getSplitScopeId();
    UUID joinScope = policy.allowParallelScopeReset() ? null : source.getJoinScopeId();
    return new ReworkPlan(
        false, null, null, nextCycle, nextIteration, itemToken, splitScope, joinScope);
  }
}
