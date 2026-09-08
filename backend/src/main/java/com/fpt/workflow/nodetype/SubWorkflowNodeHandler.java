package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

public final class SubWorkflowNodeHandler implements NodeHandler {

  @Override
  public NodeType supports() {
    return NodeType.SUB_WORKFLOW;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    Objects.requireNonNull(context, "context");
    return NodeExecutionResult.waitFor(
        new WaitDescriptor(
            "CHILD_EVENT",
            context.nodeExecutionId().toString(),
            JsonNodeFactory.instance
                .objectNode()
                .put("nodeExecutionId", context.nodeExecutionId().toString())
                .put("nodeKey", context.nodeKey())));
  }
}
