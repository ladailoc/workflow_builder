package com.fpt.workflow.sla.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.sla.domain.SlaExecution;
import com.fpt.workflow.task.domain.TaskExecution;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ManagerEscalationParticipantResolver implements EscalationParticipantResolver {
  private final SlaParticipantResolutionService participants;

  public ManagerEscalationParticipantResolver(SlaParticipantResolutionService participants) {
    this.participants = participants;
  }

  public UUID resolve(TaskExecution task, SlaExecution sla, Instant at) {
    JsonNode configured = sla.getConfigSnapshotJson().path("escalationResolver");
    var fallback = JsonNodeFactory.instance.objectNode();
    fallback.put("type", "MANAGER_OF");
    fallback.put("depth", 1);
    return participants
        .resolveAndSnapshot(task, configured, fallback, "SLA_ESCALATION", at)
        .getFirst();
  }
}
