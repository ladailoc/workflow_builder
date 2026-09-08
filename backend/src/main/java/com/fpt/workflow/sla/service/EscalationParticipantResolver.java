package com.fpt.workflow.sla.service;

import com.fpt.workflow.sla.domain.SlaExecution;
import com.fpt.workflow.task.domain.TaskExecution;
import java.time.Instant;
import java.util.UUID;

public interface EscalationParticipantResolver {
  UUID resolve(TaskExecution task, SlaExecution sla, Instant at);
}
