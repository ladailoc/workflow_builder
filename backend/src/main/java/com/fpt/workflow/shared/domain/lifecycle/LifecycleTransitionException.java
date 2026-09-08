package com.fpt.workflow.shared.domain.lifecycle;

public final class LifecycleTransitionException extends IllegalStateException {

  private final LifecycleState current;
  private final LifecycleState target;

  public LifecycleTransitionException(LifecycleState current, LifecycleState target) {
    super("Transition from " + current.name() + " to " + target.name() + " is not allowed");
    this.current = current;
    this.target = target;
  }

  public LifecycleState current() {
    return current;
  }

  public LifecycleState target() {
    return target;
  }
}
