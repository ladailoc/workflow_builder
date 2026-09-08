package com.fpt.workflow.sla.service;

import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import com.fpt.workflow.sla.domain.SlaExecution;
import com.fpt.workflow.task.domain.TaskExecution;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ManagerEscalationParticipantResolver implements EscalationParticipantResolver {
  private final OrganizationHierarchyService organizationResolver;

  public ManagerEscalationParticipantResolver(OrganizationHierarchyService organizationResolver) {
    this.organizationResolver = organizationResolver;
  }

  public UUID resolve(TaskExecution task, SlaExecution sla, Instant at) {
    if (task.getAssigneeId() == null)
      throw new IllegalStateException("Cannot escalate unassigned task");
    return organizationResolver.resolveManagerAtDepth(
        task.getAssigneeId(), 1, at.atZone(ZoneOffset.UTC).toLocalDate());
  }
}
