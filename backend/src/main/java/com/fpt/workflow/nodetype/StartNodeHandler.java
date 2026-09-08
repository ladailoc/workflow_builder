package com.fpt.workflow.nodetype;

import java.util.Objects;

/**
 * Entry node handler. Completes immediately and forwards its input snapshot to the STARTED port.
 */
public final class StartNodeHandler implements NodeHandler {

  @Override
  public NodeType supports() {
    return NodeType.START;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    Objects.requireNonNull(context, "context");
    return NodeExecutionResult.complete(context.input(), "STARTED");
  }
}
