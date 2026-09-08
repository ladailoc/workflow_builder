package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

/** Human approval task handler. Transitions the occurrence to WAITING for user decision. */
public final class ApprovalNodeHandler implements NodeHandler {

  private final NodeType nodeType;

  public ApprovalNodeHandler(NodeType nodeType) {
    this.nodeType = Objects.requireNonNull(nodeType, "nodeType");
  }

  @Override
  public NodeType supports() {
    return nodeType;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    Objects.requireNonNull(context, "context");
    return NodeExecutionResult.waitFor(
        new WaitDescriptor(
            "HUMAN_TASK",
            context.nodeExecutionId().toString(),
            JsonNodeFactory.instance
                .objectNode()
                .put("nodeExecutionId", context.nodeExecutionId().toString())
                .put("nodeKey", context.nodeKey())));
  }
}
