package com.fpt.workflow.operations.job;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class WorkflowJobHandlerRegistry {
  private final Map<String, WorkflowJobHandler> handlers;

  public WorkflowJobHandlerRegistry(List<WorkflowJobHandler> providers) {
    Map<String, WorkflowJobHandler> values = new HashMap<>();
    for (WorkflowJobHandler handler : providers) {
      if (values.putIfAbsent(handler.jobType(), handler) != null)
        throw new IllegalStateException("Duplicate job handler: " + handler.jobType());
    }
    handlers = Map.copyOf(values);
  }

  public WorkflowJobHandler require(String type) {
    WorkflowJobHandler handler = handlers.get(type);
    if (handler == null) throw new IllegalStateException("No job handler registered: " + type);
    return handler;
  }
}
