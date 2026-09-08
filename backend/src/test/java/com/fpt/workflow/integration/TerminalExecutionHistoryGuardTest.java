package com.fpt.workflow.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowCancellationPolicy;
import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowExecution;
import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowExecutionMode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerminalExecutionHistoryGuardTest {

  private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

  @Test
  void terminalIntegrationExecutionCannotBeRevivedOrRewritten() {
    var execution =
        IntegrationExecution.createRunning(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ERP",
            "CREATE_PO",
            1,
            UUID.randomUUID(),
            "logical-action",
            "idempotency-key",
            "{}",
            NOW);
    execution.markCompleted("{}", NOW.plusSeconds(1));

    assertThatThrownBy(() -> execution.markFailed(null, "{}", NOW.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal or waiting");
  }

  @Test
  void terminalSubWorkflowExecutionCannotBeRevivedOrRewritten() {
    var execution =
        SubWorkflowExecution.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            SubWorkflowExecutionMode.WAIT_FOR_COMPLETION,
            SubWorkflowCancellationPolicy.PROPAGATE,
            "{}",
            NOW);
    execution.markCompleted("{}", NOW.plusSeconds(1));

    assertThatThrownBy(() -> execution.markCancelled(NOW.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal");
  }
}
