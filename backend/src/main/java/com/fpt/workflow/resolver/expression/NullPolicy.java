package com.fpt.workflow.resolver.expression;

/** Missing/null operands are either false or explicit evaluation errors. */
public enum NullPolicy {
  NULL_IS_FALSE,
  ERROR
}
