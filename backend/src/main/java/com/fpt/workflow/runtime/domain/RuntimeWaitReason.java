package com.fpt.workflow.runtime.domain;

public enum RuntimeWaitReason {
  HUMAN_TASK,
  TIMER,
  EXTERNAL_CALLBACK,
  CHILD_EVENT,
  JOIN,
  RETRY_BACKOFF,
  MULTI_INSTANCE
}
