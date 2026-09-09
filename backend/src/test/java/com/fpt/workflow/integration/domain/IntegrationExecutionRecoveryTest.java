package com.fpt.workflow.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntegrationExecutionRecoveryTest {
  @Test
  void manualReconciliationCanBeResolvedOnceWithoutRevivingLater() {
    Instant now = Instant.parse("2026-09-09T00:00:00Z");
    IntegrationExecution execution =
        IntegrationExecution.createRunning(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ERP",
            "CREATE_PO",
            2,
            UUID.randomUUID(),
            "logical",
            "idempotency",
            "{}",
            now);
    execution.markManualReconciliation(
        IntegrationErrorCategory.LOST_RESPONSE, "{\"orderId\":\"unknown\"}", now.plusSeconds(1));

    execution.resolveManually("{\"orderId\":\"PO-42\"}", now.plusSeconds(2));

    assertThat(execution.getStatus()).isEqualTo(IntegrationExecutionStatus.COMPLETED);
    assertThat(execution.getCompletedAt()).isEqualTo(now.plusSeconds(2));
    assertThatThrownBy(() -> execution.resolveManually("{}", now.plusSeconds(3)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("awaiting manual reconciliation");
  }
}
