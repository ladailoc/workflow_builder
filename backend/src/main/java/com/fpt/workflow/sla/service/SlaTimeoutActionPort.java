package com.fpt.workflow.sla.service;

import com.fpt.workflow.sla.domain.SlaExecution;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.task.domain.TaskExecution;
import java.time.Instant;

/**
 * SLA-side seam for non-escalation timeout actions (P2-10 §15.1). The implementation may call
 * task/runtime services, while those lower-level feature packages remain independent of SLA.
 */
public interface SlaTimeoutActionPort {

  /** Supported timeout actions beyond ESCALATE/EXPIRE, per §15.1 timeoutAction vocabulary. */
  enum TimeoutAction {
    AUTO_REJECT,
    GOTO_NODE,
    CREATE_MANUAL_TASK,
    FAIL_NODE;

    public static TimeoutAction parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
        case "AUTO_REJECT", "REJECT", "AUTO-REJECT" -> AUTO_REJECT;
        case "GOTO_NODE", "GOTO", "GO_TO_NODE" -> GOTO_NODE;
        case "CREATE_MANUAL_TASK", "MANUAL_TASK", "CREATE-MANUAL-TASK" -> CREATE_MANUAL_TASK;
        case "FAIL_NODE", "FAIL-NODE" -> FAIL_NODE;
        default -> null;
      };
    }
  }

  /**
   * Executes the configured timeout action; returns the operator-facing outcome label. Optional
   * operations (a port may decline, returning empty to fall through to the next mechanism).
   */
  java.util.Optional<String> execute(
      TimeoutAction action,
      TaskExecution task,
      SlaExecution sla,
      Instant at,
      CorrelationId correlationId,
      CommandId commandId);
}
