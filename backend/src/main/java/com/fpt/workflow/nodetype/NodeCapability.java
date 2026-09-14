package com.fpt.workflow.nodetype;

/** Declarative capabilities consumed by validation and the workflow builder. */
public enum NodeCapability {
  ENTRY,
  TERMINAL,
  HUMAN_TASK,
  PARTICIPANT,
  FORM,
  ROUTING,
  ALL_MATCHING_ROUTING,
  OUTPUT,
  /** Node activation can plan/record an SLA execution from its config (human tasks). */
  SLA,
  /** Node executes an outbound connector action through the integration subsystem. */
  CONNECTOR,
  /** Node supports multi-instance fan-out (collection, item binding, completion policies). */
  MULTI_INSTANCE
}
