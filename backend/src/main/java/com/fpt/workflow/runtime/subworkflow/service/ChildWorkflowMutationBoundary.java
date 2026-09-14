package com.fpt.workflow.runtime.subworkflow.service;

import java.util.UUID;

/** Runtime-owned port used to scope child execution without depending on ticket internals. */
public interface ChildWorkflowMutationBoundary {

  BoundaryScope enterChildWorkflow(UUID childEventId, UUID parentTicketId);

  @FunctionalInterface
  interface BoundaryScope extends AutoCloseable {
    @Override
    void close();
  }
}
