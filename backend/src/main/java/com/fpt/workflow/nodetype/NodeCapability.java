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
  OUTPUT
}
