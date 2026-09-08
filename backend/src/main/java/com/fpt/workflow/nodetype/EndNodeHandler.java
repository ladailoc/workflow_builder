package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** Terminal node handler. Completes immediately with configured business outcome. */
public final class EndNodeHandler implements NodeHandler {

  @Override
  public NodeType supports() {
    return NodeType.END;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    Objects.requireNonNull(context, "context");
    ObjectNode output = JsonNodeFactory.instance.objectNode();
    if (context.configuration().hasNonNull("outcome")) {
      output.set("outcome", context.configuration().get("outcome"));
    }
    return NodeExecutionResult.complete(output, "COMPLETED");
  }
}
