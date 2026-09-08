package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.UUID;

/** Immutable handler input. Routing and activation services are intentionally absent. */
public record NodeHandlerContext(
    UUID nodeExecutionId,
    String nodeKey,
    JsonNode input,
    JsonNode configuration,
    NodeRuntimeServices runtimeServices) {

  public NodeHandlerContext(
      UUID nodeExecutionId, String nodeKey, JsonNode input, JsonNode configuration) {
    this(nodeExecutionId, nodeKey, input, configuration, NodeRuntimeServices.unavailable());
  }

  public NodeHandlerContext {
    nodeExecutionId = Objects.requireNonNull(nodeExecutionId, "nodeExecutionId");
    if (nodeKey == null || nodeKey.isBlank()) {
      throw new IllegalArgumentException("nodeKey must not be blank");
    }
    input = Objects.requireNonNull(input, "input").deepCopy();
    configuration = Objects.requireNonNull(configuration, "configuration").deepCopy();
    runtimeServices = Objects.requireNonNull(runtimeServices, "runtimeServices");
  }
}
