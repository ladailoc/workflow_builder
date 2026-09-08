package com.fpt.workflow.runtime.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.EventType;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import java.time.Instant;
import java.util.UUID;

public final class EventDtos {

  private EventDtos() {}

  public record View(
      UUID id,
      UUID ticketId,
      UUID workflowVersionId,
      UUID startedTicketRevisionId,
      EventType eventType,
      EventStatus status,
      String outcome,
      RuntimeWaitReason waitReason,
      UUID rootEventId,
      UUID parentEventId,
      UUID parentNodeExecutionId,
      UUID previousEventId,
      UUID restartedFromEventId,
      String triggerType,
      String triggerCorrelationKey,
      JsonNode variablesJson,
      UUID startedBy,
      Instant startedAt,
      Instant endedAt,
      long lockVersion) {

    public static View from(Event event) {
      return new View(
          event.getId(),
          event.getTicketId(),
          event.getWorkflowVersionId(),
          event.getStartedTicketRevisionId(),
          event.getEventType(),
          event.getStatus(),
          event.getOutcome(),
          event.getWaitReason(),
          event.getRootEventId(),
          event.getParentEventId(),
          event.getParentNodeExecutionId(),
          event.getPreviousEventId(),
          event.getRestartedFromEventId(),
          event.getTriggerType(),
          event.getTriggerCorrelationKey(),
          event.getVariablesJson(),
          event.getStartedBy(),
          event.getStartedAt(),
          event.getEndedAt(),
          event.getLockVersion());
    }
  }
}
