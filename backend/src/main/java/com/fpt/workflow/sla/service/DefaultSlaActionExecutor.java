package com.fpt.workflow.sla.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.operations.audit.*;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.*;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.sla.domain.SlaExecution;
import com.fpt.workflow.sla.repository.SlaExecutionRepository;
import com.fpt.workflow.task.domain.*;
import com.fpt.workflow.task.repository.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultSlaActionExecutor implements SlaActionExecutor {
  private final SlaExecutionRepository slas;
  private final TaskExecutionRepository tasks;
  private final TaskAssignmentHistoryRepository history;
  private final AuditEventRepository audits;
  private final EscalationParticipantResolver resolver;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public DefaultSlaActionExecutor(
      SlaExecutionRepository slas,
      TaskExecutionRepository tasks,
      TaskAssignmentHistoryRepository history,
      AuditEventRepository audits,
      EscalationParticipantResolver resolver,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.slas = slas;
    this.tasks = tasks;
    this.history = history;
    this.audits = audits;
    this.resolver = resolver;
    this.uuids = uuids;
    this.clock = clock;
  }

  @Transactional
  public ExecutionResult execute(UUID slaId, CorrelationId correlationId, CommandId commandId) {
    SlaExecution sla = slas.findByIdForUpdate(slaId).orElseThrow();
    Instant now = clock.now();
    if (!"ACTIVE".equals(sla.getStatus()))
      return new ExecutionResult(false, null, null, sla.getStatus());
    TaskExecution task = tasks.findByIdForUpdate(sla.getTaskId()).orElseThrow();
    if (task.getStatus() == TaskStatus.COMPLETED
        || task.getStatus() == TaskStatus.CANCELLED
        || task.getStatus() == TaskStatus.EXPIRED) {
      sla.complete(now);
      slas.save(sla);
      return new ExecutionResult(false, task.getAssigneeId(), null, "TASK_TERMINAL");
    }
    if (now.isBefore(sla.getDueAt()))
      return new ExecutionResult(false, task.getAssigneeId(), null, "NOT_DUE");
    if (!sla.breach(now))
      return new ExecutionResult(false, task.getAssigneeId(), null, sla.getStatus());
    UUID from = task.getAssigneeId(), to = resolver.resolve(task, sla, now);
    task.reassign(to);
    tasks.save(task);
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("slaExecutionId", slaId.toString());
    metadata.put("originalAssignee", from.toString());
    metadata.put("newAssignee", to.toString());
    metadata.put("reason", "SLA_ESCALATION");
    history.save(
        TaskAssignmentHistory.create(
            uuids.generate(),
            task.getId(),
            TaskAssignmentAction.REASSIGN,
            from,
            to,
            to,
            "SLA_ESCALATION",
            metadata,
            now));
    audits.save(
        AuditEvent.record(
            uuids.generate(),
            "TASK_EXECUTION",
            task.getId(),
            "TASK_AUTO_REASSIGNED",
            to,
            to,
            correlationId,
            commandId,
            metadata,
            now));
    slas.save(sla);
    return new ExecutionResult(true, from, to, sla.getStatus());
  }

  @Override
  @Transactional
  public List<ExecutionResult> executeDue(
      Instant at, CorrelationId correlationId, CommandId commandId) {
    List<SlaExecution> dueList = slas.findAllByStatusAndNextActionAtLessThanEqual("ACTIVE", at);
    List<ExecutionResult> results = new ArrayList<>();
    for (SlaExecution due : dueList) {
      results.add(execute(due.getId(), correlationId, commandId));
    }
    return List.copyOf(results);
  }
}
