package com.fpt.workflow.operations.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetentionPlannerTest {

  private final RetentionPlanner planner = new RetentionPlanner();
  private final Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void retainsUntilCategorySpecificWindowExpires() {
    RetentionPolicy policy =
        RetentionPolicy.create(
            UUID.randomUUID(),
            RetentionCategory.NOTIFICATION,
            30,
            RetentionExpiryAction.MASK,
            true,
            createdAt);

    assertThat(planner.decide(policy, createdAt, createdAt.plusSeconds(29 * 86_400L), false))
        .isEqualTo(RetentionDecision.RETAIN);
    assertThat(planner.decide(policy, createdAt, createdAt.plusSeconds(30 * 86_400L), false))
        .isEqualTo(RetentionDecision.MASK);
  }

  @Test
  void referencedRuntimeHistoryIsAnonymizedInsteadOfHardDeleted() {
    RetentionPolicy policy =
        RetentionPolicy.create(
            UUID.randomUUID(),
            RetentionCategory.EVENT,
            1,
            RetentionExpiryAction.HARD_DELETE,
            true,
            createdAt);

    assertThat(planner.decide(policy, createdAt, createdAt.plusSeconds(86_400L), true))
        .isEqualTo(RetentionDecision.ANONYMIZE);
    assertThat(planner.decide(policy, createdAt, createdAt.plusSeconds(86_400L), false))
        .isEqualTo(RetentionDecision.HARD_DELETE);
  }

  @Test
  void disabledPolicyAlwaysRetains() {
    RetentionPolicy policy =
        RetentionPolicy.create(
            UUID.randomUUID(),
            RetentionCategory.AUDIT,
            1,
            RetentionExpiryAction.HARD_DELETE,
            false,
            createdAt);

    assertThat(planner.decide(policy, createdAt, createdAt.plusSeconds(365 * 86_400L), false))
        .isEqualTo(RetentionDecision.RETAIN);
  }

  @Test
  void supportsCategorySpecificPoliciesForEveryCategory() {
    for (RetentionCategory category : RetentionCategory.values()) {
      RetentionPolicy policy =
          RetentionPolicy.create(
              UUID.randomUUID(),
              category,
              7,
              category == RetentionCategory.AUDIT
                  ? RetentionExpiryAction.MASK
                  : RetentionExpiryAction.ANONYMIZE,
              true,
              createdAt);

      assertThat(planner.decide(policy, createdAt, createdAt.plusSeconds(6 * 86_400L), false))
          .isEqualTo(RetentionDecision.RETAIN);

      RetentionDecision expiredDecision =
          planner.decide(policy, createdAt, createdAt.plusSeconds(7 * 86_400L), false);
      assertThat(expiredDecision).isNotEqualTo(RetentionDecision.RETAIN);
    }
  }

  @Test
  void retentionDecisionsAreDeterministicAndIdempotent() {
    RetentionPolicy policy =
        RetentionPolicy.create(
            UUID.randomUUID(),
            RetentionCategory.TASK_SUBMISSION,
            14,
            RetentionExpiryAction.MASK,
            true,
            createdAt);

    Instant testTime = createdAt.plusSeconds(14 * 86_400L);
    RetentionDecision first = planner.decide(policy, createdAt, testTime, true);
    RetentionDecision second = planner.decide(policy, createdAt, testTime, true);
    assertThat(first).isEqualTo(second).isEqualTo(RetentionDecision.MASK);
  }
}
