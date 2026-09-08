package com.fpt.workflow.operations.outbox;

public enum OutboxStatus {
  READY,
  PUBLISHING,
  RETRY,
  PUBLISHED,
  DEAD
}
