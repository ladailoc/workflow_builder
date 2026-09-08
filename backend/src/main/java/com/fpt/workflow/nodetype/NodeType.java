package com.fpt.workflow.nodetype;

/** Stable identifiers for the initial P0 node capabilities. */
public enum NodeType {
  START,
  END,
  APPROVAL,
  REVIEW,
  CONDITION,
  PARALLEL_SPLIT,
  JOIN,
  SYSTEM_ACTION,
  SUB_WORKFLOW,
  NOTIFICATION
}
