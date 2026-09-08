package com.fpt.workflow.definition.validation;

import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.rework.ReworkPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Tarjan SCC analysis used only at design time; runtime remains token-driven. */
@Component
public final class ControlledCycleAnalyzer {

  public List<ControlledCycleIssue> analyze(
      List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
    Map<UUID, List<UUID>> adjacency = new HashMap<>();
    nodes.forEach(node -> adjacency.put(node.getId(), new ArrayList<>()));
    edges.forEach(
        edge ->
            adjacency
                .computeIfAbsent(edge.getSourceNodeId(), ignored -> new ArrayList<>())
                .add(edge.getTargetNodeId()));
    List<Set<UUID>> components = stronglyConnected(adjacency);
    List<ControlledCycleIssue> issues = new ArrayList<>();
    for (Set<UUID> component : components) {
      List<EdgeDefinition> internal =
          edges.stream()
              .filter(
                  edge ->
                      component.contains(edge.getSourceNodeId())
                          && component.contains(edge.getTargetNodeId()))
              .toList();
      boolean cyclic =
          component.size() > 1
              || internal.stream()
                  .anyMatch(edge -> edge.getSourceNodeId().equals(edge.getTargetNodeId()));
      if (!cyclic) continue;
      List<EdgeDefinition> intentional =
          internal.stream()
              .filter(
                  edge ->
                      edge.getTransitionType() == TransitionType.REWORK
                          || edge.getTransitionType() == TransitionType.RETURN)
              .toList();
      if (intentional.isEmpty()) {
        EdgeDefinition resource = internal.getFirst();
        issues.add(
            new ControlledCycleIssue(
                "CYCLE_NOT_INTENTIONAL",
                resource.getId(),
                "/transitionType",
                "Cycle requires an explicit REWORK or RETURN edge"));
        continue;
      }

      // Check if there is any cycle within this SCC formed strictly by non-rework
      // (normal/conditional) edges
      List<EdgeDefinition> normalEdges =
          internal.stream()
              .filter(
                  edge ->
                      edge.getTransitionType() != TransitionType.REWORK
                          && edge.getTransitionType() != TransitionType.RETURN)
              .toList();
      Map<UUID, List<UUID>> normalAdjacency = new HashMap<>();
      component.forEach(nodeId -> normalAdjacency.put(nodeId, new ArrayList<>()));
      normalEdges.forEach(
          edge ->
              normalAdjacency
                  .computeIfAbsent(edge.getSourceNodeId(), ignored -> new ArrayList<>())
                  .add(edge.getTargetNodeId()));
      List<Set<UUID>> normalComponents = stronglyConnected(normalAdjacency);
      for (Set<UUID> normalSub : normalComponents) {
        boolean subCyclic =
            normalSub.size() > 1
                || normalEdges.stream()
                    .anyMatch(
                        edge ->
                            normalSub.contains(edge.getSourceNodeId())
                                && edge.getSourceNodeId().equals(edge.getTargetNodeId()));
        if (subCyclic) {
          EdgeDefinition badEdge =
              normalEdges.stream()
                  .filter(
                      edge ->
                          normalSub.contains(edge.getSourceNodeId())
                              && normalSub.contains(edge.getTargetNodeId()))
                  .findFirst()
                  .orElse(internal.getFirst());
          issues.add(
              new ControlledCycleIssue(
                  "CYCLE_NOT_INTENTIONAL",
                  badEdge.getId(),
                  "/transitionType",
                  "Cycle requires an explicit REWORK or RETURN edge"));
        }
      }

      intentional.forEach(edge -> validatePolicy(edge, issues));
    }
    return List.copyOf(issues);
  }

  private void validatePolicy(EdgeDefinition edge, List<ControlledCycleIssue> issues) {
    try {
      ReworkPolicy.fromEdgeConfig(edge.getConfigJson());
    } catch (IllegalArgumentException exception) {
      issues.add(
          new ControlledCycleIssue(
              "REWORK_POLICY_INVALID",
              edge.getId(),
              "/config/reworkPolicy",
              exception.getMessage()));
    }
  }

  private List<Set<UUID>> stronglyConnected(Map<UUID, List<UUID>> adjacency) {
    Map<UUID, Integer> index = new HashMap<>();
    Map<UUID, Integer> low = new HashMap<>();
    Deque<UUID> stack = new ArrayDeque<>();
    Set<UUID> onStack = new HashSet<>();
    List<Set<UUID>> result = new ArrayList<>();
    int[] next = {0};
    adjacency
        .keySet()
        .forEach(
            node -> {
              if (!index.containsKey(node)) {
                connect(node, adjacency, index, low, stack, onStack, result, next);
              }
            });
    return result;
  }

  private void connect(
      UUID node,
      Map<UUID, List<UUID>> adjacency,
      Map<UUID, Integer> index,
      Map<UUID, Integer> low,
      Deque<UUID> stack,
      Set<UUID> onStack,
      List<Set<UUID>> result,
      int[] next) {
    index.put(node, next[0]);
    low.put(node, next[0]++);
    stack.push(node);
    onStack.add(node);
    for (UUID target : adjacency.getOrDefault(node, List.of())) {
      if (!index.containsKey(target)) {
        connect(target, adjacency, index, low, stack, onStack, result, next);
        low.put(node, Math.min(low.get(node), low.get(target)));
      } else if (onStack.contains(target)) {
        low.put(node, Math.min(low.get(node), index.get(target)));
      }
    }
    if (low.get(node).equals(index.get(node))) {
      Set<UUID> component = new HashSet<>();
      UUID current;
      do {
        current = stack.pop();
        onStack.remove(current);
        component.add(current);
      } while (!current.equals(node));
      result.add(Set.copyOf(component));
    }
  }
}
