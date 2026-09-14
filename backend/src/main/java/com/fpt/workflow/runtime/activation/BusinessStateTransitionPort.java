package com.fpt.workflow.runtime.activation;
import java.util.UUID;
/**
 * Runtime-facing business display state transition. Implemented by a ticket-owned service so that
 * an aggregate invariant (one closed row, one cached current row) is maintained transactionally, but
 * the activation/rerun path never hard-depends on the ticket feature.
 */
public interface BusinessStateTransitionPort {
  /** Idempotent for the producing (eventId,stateKey) pair — closes current interval and appends one row. */
  void transitionIfChanged(UUID ticketId, UUID eventId, UUID workflowVersionId, String stateKey, UUID sourceNodeExecutionId);
}
