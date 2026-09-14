package com.fpt.workflow.monitoring;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Detects organization drift for active tasks without mutating assignment snapshots or assignees. */
@Component
@EnableScheduling
@ConditionalOnProperty(
    name = "platform.participant.inactive-monitor.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InactiveAssigneeMonitor {

  private static final Logger LOGGER = LoggerFactory.getLogger(InactiveAssigneeMonitor.class);
  private static final List<TaskStatus> ACTIVE_STATUSES =
      List.of(TaskStatus.READY, TaskStatus.CLAIMED, TaskStatus.IN_PROGRESS);

  private final TaskExecutionRepository tasks;
  private final EmployeeRepository employees;
  private final AuditEventRepository audits;
  private final PlatformClock clock;

  public InactiveAssigneeMonitor(
      TaskExecutionRepository tasks,
      EmployeeRepository employees,
      AuditEventRepository audits,
      PlatformClock clock) {
    this.tasks = tasks;
    this.employees = employees;
    this.audits = audits;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${platform.participant.inactive-monitor.initial-delay-ms:60000}",
      fixedDelayString = "${platform.participant.inactive-monitor.fixed-delay-ms:60000}")
  public void poll() {
    try {
      detectInactiveAssignees();
    } catch (RuntimeException failure) {
      LOGGER.error("Inactive assignee monitoring failed", failure);
    }
  }

  public int detectInactiveAssignees() {
    int alerts = 0;
    for (TaskStatus status : ACTIVE_STATUSES) {
      for (TaskExecution task : tasks.findAllByStatusOrderByCreatedAtDesc(status)) {
        UUID assigneeId = task.getAssigneeId();
        if (assigneeId == null
            || employees.findByUserId(assigneeId).map(employee -> employee.isActive()).orElse(false)) {
          continue;
        }
        if (hasCurrentAlert(task.getId(), assigneeId)) {
          continue;
        }
        try {
          audits.saveAndFlush(alert(task, assigneeId));
          alerts++;
        } catch (DataIntegrityViolationException duplicateRace) {
          LOGGER.debug("Inactive assignee alert already recorded for task {}", task.getId());
        }
      }
    }
    return alerts;
  }

  private boolean hasCurrentAlert(UUID taskId, UUID assigneeId) {
    return audits
        .findAllByAggregateTypeAndAggregateIdOrderByOccurredAtAsc("TASK_EXECUTION", taskId)
        .stream()
        .anyMatch(
            audit ->
                "ASSIGNEE_INACTIVE".equals(audit.getEventType())
                    && assigneeId
                        .toString()
                        .equals(audit.getMetadataJson().path("assigneeId").asText()));
  }

  private AuditEvent alert(TaskExecution task, UUID assigneeId) {
    String identity = task.getId() + ":" + assigneeId + ":ASSIGNEE_INACTIVE";
    UUID deterministicId =
        UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("taskId", task.getId().toString());
    metadata.put("nodeExecutionId", task.getNodeExecutionId().toString());
    metadata.put("assigneeId", assigneeId.toString());
    metadata.put("reason", "ASSIGNEE_INACTIVE");
    metadata.put("requiresExplicitReassignment", true);
    return AuditEvent.record(
        deterministicId,
        "TASK_EXECUTION",
        task.getId(),
        "ASSIGNEE_INACTIVE",
        null,
        null,
        new CorrelationId(deterministicId),
        null,
        metadata,
        clock.now());
  }
}
