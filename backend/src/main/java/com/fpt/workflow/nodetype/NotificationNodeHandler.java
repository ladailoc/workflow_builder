package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Enqueues a durable logical notification; routing remains owned by RoutingService. */
public final class NotificationNodeHandler implements NodeHandler {
  @Override
  public NodeType supports() {
    return NodeType.NOTIFICATION;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    String dedupKey = "notification-node:" + context.nodeExecutionId();
    context
        .runtimeServices()
        .scheduleNotification(
            context.nodeExecutionId(), context.input(), context.configuration(), dedupKey);
    var output = JsonNodeFactory.instance.objectNode();
    output.put("dispatch", "QUEUED");
    output.put("dedupKey", dedupKey);
    return NodeExecutionResult.complete(output, "QUEUED");
  }
}
