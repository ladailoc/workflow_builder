package com.fpt.workflow.shared.domain.lifecycle;

import java.util.Collection;
import java.util.Objects;

public final class TransitionGuard {

  private TransitionGuard() {}

  public static <S extends Enum<S> & LifecycleState> boolean isAllowed(
      S current, S target, Collection<S> allowedTargets) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(allowedTargets, "allowedTargets");
    return current != target && allowedTargets.contains(target);
  }

  public static <S extends Enum<S> & LifecycleState> void requireAllowed(
      S current, S target, Collection<S> allowedTargets) {
    if (!isAllowed(current, target, allowedTargets)) {
      throw new LifecycleTransitionException(current, target);
    }
  }
}
