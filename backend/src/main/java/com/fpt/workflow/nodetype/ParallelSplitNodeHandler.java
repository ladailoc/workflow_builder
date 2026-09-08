package com.fpt.workflow.nodetype;

import java.util.Objects;

/** Emits one split port; ALL_OUTGOING edge routing owns all branch destinations. */
public final class ParallelSplitNodeHandler implements NodeHandler {
  @Override
  public NodeType supports() {
    return NodeType.PARALLEL_SPLIT;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    Objects.requireNonNull(context, "context");
    return NodeExecutionResult.complete(context.input(), "SPLIT");
  }
}
