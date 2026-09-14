package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

/**
 * JOIN gateway handler. Join aggregation (arrival counting, policy evaluation, remaining-branch
 * handling and exactly-once downstream routing) is owned by {@code JoinService.arrive}, invoked
 * from the routing path when an activation token reaches a JOIN node. This handler exists so the
 * node manifest contract exposes the real JOIN behavior: if a JOIN occurrence is ever dispatched
 * through the plain activation path it waits for join arrivals instead of failing the runtime.
 */
public final class JoinNodeHandler implements NodeHandler {

  @Override
  public NodeType supports() {
    return NodeType.JOIN;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    Objects.requireNonNull(context, "context");
    return NodeExecutionResult.waitFor(
        new WaitDescriptor(
            "JOIN",
            context.nodeExecutionId().toString(),
            JsonNodeFactory.instance
                .objectNode()
                .put("nodeExecutionId", context.nodeExecutionId().toString())
                .put("nodeKey", context.nodeKey())));
  }
}
