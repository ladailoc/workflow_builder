package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public final class SystemActionNodeHandler implements NodeHandler {

  @Override
  public NodeType supports() {
    return NodeType.SYSTEM_ACTION;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    Objects.requireNonNull(context, "context");
    ObjectNode jobPayload = JsonNodeFactory.instance.objectNode();
    jobPayload.put("nodeExecutionId", context.nodeExecutionId().toString());
    jobPayload.put("correlationId", context.runtimeServices().newId().toString());
    jobPayload.put("commandId", context.runtimeServices().newId().toString());
    context
        .runtimeServices()
        .scheduleDurableJob(
            "SYSTEM_ACTION_EXECUTE",
            context.nodeExecutionId(),
            jobPayload,
            100,
            context.runtimeServices().now(),
            "system-action:" + context.nodeExecutionId());
    return NodeExecutionResult.waitFor(
        new WaitDescriptor(
            "RETRY_BACKOFF",
            context.nodeExecutionId().toString(),
            JsonNodeFactory.instance
                .objectNode()
                .put("nodeExecutionId", context.nodeExecutionId().toString())
                .put("nodeKey", context.nodeKey())));
  }

  public NodeExecutionResult mapOutcome(boolean success, JsonNode payload, String errorMessage) {
    ObjectNode output = JsonNodeFactory.instance.objectNode();
    if (success) {
      if (payload instanceof ObjectNode obj) {
        output.setAll(obj);
      } else if (payload != null) {
        output.set("result", payload);
      }
      return NodeExecutionResult.complete(output, "SUCCESS");
    } else {
      output.put("errorMessage", errorMessage != null ? errorMessage : "Action execution failed");
      if (payload != null) {
        output.set("details", payload);
      }
      return NodeExecutionResult.complete(output, "ERROR");
    }
  }
}
