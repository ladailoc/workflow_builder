package com.fpt.workflow.runtime.context;

import java.util.Objects;
import java.util.UUID;

public record TicketSubjectSnapshot(
    String subjectType, UUID subjectRefId, String roleKey, String sourceField) {

  public TicketSubjectSnapshot {
    subjectType = Objects.requireNonNull(subjectType, "subjectType");
    subjectRefId = Objects.requireNonNull(subjectRefId, "subjectRefId");
    roleKey = Objects.requireNonNull(roleKey, "roleKey");
  }
}
