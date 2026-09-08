package com.fpt.workflow.definition.domain;

public final class StaleDraftRevisionException extends IllegalStateException {

  private final long expectedRevision;
  private final long currentRevision;

  public StaleDraftRevisionException(long expectedRevision, long currentRevision) {
    super(
        "Expected workflow draft revision "
            + expectedRevision
            + " but current revision is "
            + currentRevision);
    this.expectedRevision = expectedRevision;
    this.currentRevision = currentRevision;
  }

  public long expectedRevision() {
    return expectedRevision;
  }

  public long currentRevision() {
    return currentRevision;
  }
}
