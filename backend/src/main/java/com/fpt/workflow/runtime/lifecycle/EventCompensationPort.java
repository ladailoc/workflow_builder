package com.fpt.workflow.runtime.lifecycle;

import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;

/**
 * Runtime-owned outbound port for explicit compensation planning after cancellation or
 * termination. Integration supplies the adapter, so runtime does not depend on integration.
 */
public interface EventCompensationPort {

  enum CompensationPolicy {
    NONE,
    MANUAL,
    ACTION;

    public static CompensationPolicy parse(String raw) {
      if (raw == null || raw.isBlank()) return NONE;
      return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
        case "MANUAL" -> MANUAL;
        case "ACTION" -> ACTION;
        default -> NONE;
      };
    }
  }

  void planForCancelledEvent(
      Event event, String reason, CorrelationId correlationId, CommandId commandId);
}
