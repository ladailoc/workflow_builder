package com.fpt.workflow.operations.retention;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Pure retention decision engine. Deletion workers must re-check references before mutation. */
@Component
public class RetentionPlanner {

  public RetentionDecision decide(
      RetentionPolicy policy, Instant artifactTime, Instant now, boolean runtimeHistoryReferenced) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(artifactTime, "artifactTime");
    Objects.requireNonNull(now, "now");
    if (!policy.isEnabled()
        || now.isBefore(artifactTime.plus(policy.getRetentionDays(), ChronoUnit.DAYS))) {
      return RetentionDecision.RETAIN;
    }
    return switch (policy.getExpiryAction()) {
      case MASK -> RetentionDecision.MASK;
      case ANONYMIZE -> RetentionDecision.ANONYMIZE;
      case HARD_DELETE ->
          runtimeHistoryReferenced ? RetentionDecision.ANONYMIZE : RetentionDecision.HARD_DELETE;
    };
  }
}
