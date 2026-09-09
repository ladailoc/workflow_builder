package com.fpt.workflow.task.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskAssignmentAction;
import com.fpt.workflow.task.domain.TaskAssignmentHistory;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.dto.TaskDtos;
import com.fpt.workflow.task.repository.TaskAssignmentHistoryRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskQueryService {

  private final TaskExecutionRepository taskRepository;
  private final TaskAssignmentHistoryRepository assignmentHistoryRepository;
  private final NodeExecutionRepository nodeExecutionRepository;
  private final EventRepository eventRepository;
  private final TaskCommandService taskCommandService;
  private final ActorContextProvider actorContextProvider;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;

  public TaskQueryService(
      TaskExecutionRepository taskRepository,
      TaskAssignmentHistoryRepository assignmentHistoryRepository,
      NodeExecutionRepository nodeExecutionRepository,
      EventRepository eventRepository,
      TaskCommandService taskCommandService,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock) {
    this.taskRepository = taskRepository;
    this.assignmentHistoryRepository = assignmentHistoryRepository;
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.eventRepository = eventRepository;
    this.taskCommandService = taskCommandService;
    this.actorContextProvider = actorContextProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  @PreAuthorize("isAuthenticated()")
  public List<TaskDtos.TaskItemView> listTasks(String statusFilter) {
    ActorContext actor = actorContextProvider.requireActor();
    TaskStatus status = parseStatus(statusFilter);

    List<TaskExecution> tasks;
    if (actor.hasRole(RoleKey.ADMIN) || actor.hasRole(RoleKey.OPERATOR)) {
      if (status != null) {
        tasks = taskRepository.findAllByStatusOrderByCreatedAtDesc(status);
      } else {
        tasks = taskRepository.findAllByOrderByCreatedAtDesc();
      }
    } else {
      if (status != null) {
        tasks =
            taskRepository.findAllByAssigneeIdAndStatusOrderByCreatedAtDesc(
                actor.actorId(), status);
      } else {
        tasks = taskRepository.findAllByAssigneeIdOrderByCreatedAtDesc(actor.actorId());
      }
    }

    Map<UUID, NodeExecution> nodeCache = new ConcurrentHashMap<>();
    Map<UUID, Event> eventCache = new ConcurrentHashMap<>();

    return tasks.stream().map(task -> toView(task, nodeCache, eventCache)).toList();
  }

  @Transactional
  @PreAuthorize("isAuthenticated()")
  public TaskDtos.TaskItemView executeAction(
      UUID taskId,
      String action,
      TaskDtos.TaskActionRequest request,
      CommandId commandId,
      CorrelationId correlationId) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(action, "action");
    ActorContext actor = actorContextProvider.requireActor();

    String normalizedAction = action.trim().toLowerCase(Locale.ROOT);
    Instant now = clock.now();

    if ("claim".equals(normalizedAction)) {
      TaskExecution task =
          taskRepository
              .findByIdForUpdate(taskId)
              .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
      UUID previousAssignee = task.getAssigneeId();

      assignmentHistoryRepository.saveAndFlush(
          TaskAssignmentHistory.create(
              uuidGenerator.generate(),
              task.getId(),
              TaskAssignmentAction.CLAIM,
              previousAssignee,
              actor.actorId(),
              actor.actorId(),
              request != null ? request.comment() : null,
              JsonNodeFactory.instance.objectNode(),
              now));

      task.claim(actor.actorId());
      taskRepository.saveAndFlush(task);
      return toView(task);
    }

    if ("reassign".equals(normalizedAction)) {
      TaskExecution task =
          taskRepository
              .findByIdForUpdate(taskId)
              .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
      if (!actor.hasRole(RoleKey.ADMIN) && !actor.hasRole(RoleKey.OPERATOR)) {
        if (task.getAssigneeId() != null && !task.getAssigneeId().equals(actor.actorId())) {
          throw new AccessDeniedException("Not authorized to reassign task: " + taskId);
        }
      }
      UUID targetUserId = request != null ? request.targetUserId() : null;
      if (targetUserId == null) {
        throw new IllegalArgumentException("targetUserId is required for reassign action");
      }
      UUID previousAssignee = task.getAssigneeId();

      String reason =
          request != null && request.comment() != null && !request.comment().isBlank()
              ? request.comment()
              : "Reassigned by " + actor.principalName();

      TaskAssignmentAction actionType =
          previousAssignee == null ? TaskAssignmentAction.ASSIGN : TaskAssignmentAction.REASSIGN;

      assignmentHistoryRepository.saveAndFlush(
          TaskAssignmentHistory.create(
              uuidGenerator.generate(),
              task.getId(),
              actionType,
              previousAssignee,
              targetUserId,
              actor.actorId(),
              reason,
              JsonNodeFactory.instance.objectNode(),
              now));

      task.reassign(targetUserId);
      taskRepository.saveAndFlush(task);
      return toView(task);
    }

    BusinessOutcome outcome =
        switch (normalizedAction) {
          case "approve" -> BusinessOutcome.APPROVED;
          case "reject" -> BusinessOutcome.REJECTED;
          case "request-revision" -> BusinessOutcome.RETURNED;
          case "complete" -> BusinessOutcome.SUCCESS;
          default -> BusinessOutcome.of(action.toUpperCase(Locale.ROOT));
        };

    var decisionResult =
        taskCommandService.decideTask(
            taskId,
            outcome,
            request != null ? request.formData() : null,
            request != null ? request.comment() : null,
            correlationId,
            commandId);

    return toView(decisionResult.task());
  }

  public TaskDtos.TaskItemView toView(TaskExecution task) {
    return toView(task, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
  }

  private TaskDtos.TaskItemView toView(
      TaskExecution task, Map<UUID, NodeExecution> nodeCache, Map<UUID, Event> eventCache) {
    UUID eventId = null;
    UUID ticketId = null;

    if (task.getNodeExecutionId() != null) {
      NodeExecution node =
          nodeCache.computeIfAbsent(
              task.getNodeExecutionId(), id -> nodeExecutionRepository.findById(id).orElse(null));
      if (node != null) {
        eventId = node.getEventId();
        if (eventId != null) {
          Event event =
              eventCache.computeIfAbsent(eventId, id -> eventRepository.findById(id).orElse(null));
          if (event != null) {
            ticketId = event.getTicketId();
          }
        }
      }
    }

    return new TaskDtos.TaskItemView(
        task.getId(),
        task.getNodeExecutionId(),
        ticketId,
        eventId,
        task.getTitleSnapshot(),
        task.getDescriptionSnapshot(),
        task.getStatus(),
        task.getOutcome(),
        task.getPriority(),
        task.getAssigneeId(),
        task.getDueAt(),
        task.getCreatedAt(),
        task.getCompletedAt(),
        task.getLockVersion(),
        task.getFormSchemaJson(),
        task.getInputSnapshotJson());
  }

  private TaskStatus parseStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return TaskStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
