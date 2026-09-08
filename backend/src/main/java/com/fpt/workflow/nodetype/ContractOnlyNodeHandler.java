package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

/**
 * Runtime placeholder for the manifest wave; the workflow runtime engine is intentionally absent.
 */
final class ContractOnlyNodeHandler implements NodeHandler {

  private final NodeType nodeType;

  ContractOnlyNodeHandler(NodeType nodeType) {
    this.nodeType = Objects.requireNonNull(nodeType, "nodeType");
  }

  @Override
  public NodeType supports() {
    return nodeType;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    Objects.requireNonNull(context, "context");
    return NodeExecutionResult.fail(
        new NodeExecutionError(
            "NODE.RUNTIME_NOT_IMPLEMENTED",
            "Runtime execution is outside the node manifest capability wave",
            JsonNodeFactory.instance.objectNode()));
  }
}
