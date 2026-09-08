package com.fpt.workflow.runtime.routing;

import com.fpt.workflow.runtime.domain.NodeExecution;
import java.util.List;
import java.util.UUID;

public record RoutingResult(
    RoutingMode mode, List<UUID> selectedEdgeIds, List<NodeExecution> activations) {

  public RoutingResult {
    selectedEdgeIds = List.copyOf(selectedEdgeIds);
    activations = List.copyOf(activations);
  }
}
