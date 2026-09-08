package com.fpt.workflow.shared.domain.lifecycle;

/** Common marker for canonical lifecycle enums; business outcomes are separate values. */
public interface LifecycleState {

  String name();
}
