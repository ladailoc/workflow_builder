package com.fpt.workflow.nodetype;

/** Executes one node occurrence without selecting or activating a routing destination. */
public interface NodeHandler {
  NodeType supports();

  NodeExecutionResult execute(NodeHandlerContext context);
}
