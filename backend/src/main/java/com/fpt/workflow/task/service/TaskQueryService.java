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
import java.util.Comparator;
import java.util.LinkedHashMap;
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
  private final com.fpt.workflow.task.repository.TaskCandidateRepository candidateRepository;

  public TaskQueryService(
      TaskExecutionRepository taskRepository,
      TaskAssignmentHistoryRepository assignmentHistoryRepository,
      NodeExecutionRepository nodeExecutionRepository,
      EventRepository eventRepository,
      TaskCommandService taskCommandService,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock) {
    this(
        taskRepository,
        assignmentHistoryRepository,
        nodeExecutionRepository,
        eventRepository,
        taskCommandService,
        actorContextProvider,
        uuidGenerator,
        clock,
        null);
  }

  private final com.fpt.workflow.operations.audit.AuditEventRepository auditRepository;

  public TaskQueryService(
      TaskExecutionRepository taskRepository,
      TaskAssignmentHistoryRepository assignmentHistoryRepository,
      NodeExecutionRepository nodeExecutionRepository,
      EventRepository eventRepository,
      TaskCommandService taskCommandService,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      com.fpt.workflow.task.repository.TaskCandidateRepository candidateRepository) {
    this(
        taskRepository,
        assignmentHistoryRepository,
        nodeExecutionRepository,
        eventRepository,
        taskCommandService,
        actorContextProvider,
        uuidGenerator,
        clock,
        candidateRepository,
        null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public TaskQueryService(
      TaskExecutionRepository taskRepository,
      TaskAssignmentHistoryRepository assignmentHistoryRepository,
      NodeExecutionRepository nodeExecutionRepository,
      EventRepository eventRepository,
      TaskCommandService taskCommandService,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      com.fpt.workflow.task.repository.TaskCandidateRepository candidateRepository,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          com.fpt.workflow.operations.audit.AuditEventRepository auditRepository) {
    this.taskRepository = taskRepository;
    this.assignmentHistoryRepository = assignmentHistoryRepository;
    this.nodeExecutionRepository = nodeExecutionRepository;
    this.eventRepository = eventRepository;
    this.taskCommandService = taskCommandService;
    this.actorContextProvider = actorContextProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.candidateRepository = candidateRepository;
    this.auditRepository = auditRepository;
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

      if (candidateRepository != null) {
        List<UUID> candidateTaskIds =
            candidateRepository.findAllByUserIdOrderByCreatedAtAsc(actor.actorId()).stream()
                .filter(TaskQueryService::isClaimableCandidate)
                .map(com.fpt.workflow.task.domain.TaskCandidate::getTaskId)
                .distinct()
                .toList();
        if (!candidateTaskIds.isEmpty()) {
          Map<UUID, TaskExecution> visibleTasks = new LinkedHashMap<>();
          tasks.forEach(task -> visibleTasks.put(task.getId(), task));
          taskRepository.findAllById(candidateTaskIds).stream()
              .filter(task -> status == null || task.getStatus() == status)
              .filter(
                  task ->
                      task.getAssigneeId() == null
                          || task.getAssigneeId().equals(actor.actorId()))
              .forEach(task -> visibleTasks.putIfAbsent(task.getId(), task));
          tasks =
              visibleTasks.values().stream()
                  .sorted(Comparator.comparing(TaskExecution::getCreatedAt).reversed())
                  .toList();
        }
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

      if (task.getStatus() == TaskStatus.COMPLETED
          || task.getStatus() == TaskStatus.CANCELLED
          || task.getStatus() == TaskStatus.EXPIRED) {
        throw new IllegalStateException("Task is already terminal: " + task.getStatus());
      }

      if (candidateRepository != null) {
        List<com.fpt.workflow.task.domain.TaskCandidate> candidates =
            candidateRepository.findAllByTaskIdOrderByCreatedAtAsc(taskId);
        List<com.fpt.workflow.task.domain.TaskCandidate> claimableCandidates =
            candidates.stream().filter(TaskQueryService::isClaimableCandidate).toList();
        if (!claimableCandidates.isEmpty()) {
          boolean isCandidate =
              claimableCandidates.stream().anyMatch(c -> c.getUserId().equals(actor.actorId()));
          boolean isPrivileged =
              actor.hasRole(RoleKey.ADMIN) || actor.hasRole(RoleKey.OPERATOR);
          if (!isCandidate && !isPrivileged) {
            throw new AccessDeniedException(
                "Actor is not an eligible candidate for task: " + taskId);
          }
        }
      }

      boolean isPrivileged = actor.hasRole(RoleKey.ADMIN) || actor.hasRole(RoleKey.OPERATOR);
      if (task.getAssigneeId() != null
          && !task.getAssigneeId().equals(actor.actorId())
          && !isPrivileged) {
        throw new AccessDeniedException("Task is assigned to a different user: " + taskId);
      }

      if (task.getAssigneeId() != null && task.getAssigneeId().equals(actor.actorId())) {
        return toView(task);
      }

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
      auditTaskEvent(task, "TASK_CLAIMED", actor, now);
      return toView(task);
    }

    if ("unclaim".equals(normalizedAction)) {
      TaskExecution task =
          taskRepository
              .findByIdForUpdate(taskId)
              .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
      if (task.getStatus() == TaskStatus.COMPLETED
          || task.getStatus() == TaskStatus.CANCELLED
          || task.getStatus() == TaskStatus.EXPIRED) {
        throw new IllegalStateException("Task is already terminal: " + task.getStatus());
      }
      if (!actor.hasRole(RoleKey.ADMIN) && !actor.hasRole(RoleKey.OPERATOR)) {
        if (task.getAssigneeId() == null || !task.getAssigneeId().equals(actor.actorId())) {
          throw new AccessDeniedException("Not authorized to unclaim task: " + taskId);
        }
      }
      UUID previousAssignee = task.getAssigneeId();

      assignmentHistoryRepository.saveAndFlush(
          TaskAssignmentHistory.create(
              uuidGenerator.generate(),
              task.getId(),
              TaskAssignmentAction.UNCLAIM,
              previousAssignee,
              null,
              actor.actorId(),
              request != null ? request.comment() : null,
              JsonNodeFactory.instance.objectNode(),
              now));

      task.unclaim();
      taskRepository.saveAndFlush(task);
      auditTaskEvent(task, "TASK_UNCLAIMED", actor, now);
      return toView(task);
    }

    if ("reassign".equals(normalizedAction)) {
      TaskExecution task =
          taskRepository
              .findByIdForUpdate(taskId)
              .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
      if (task.getStatus() == TaskStatus.COMPLETED
          || task.getStatus() == TaskStatus.CANCELLED
          || task.getStatus() == TaskStatus.EXPIRED) {
        throw new IllegalStateException("Task is already terminal: " + task.getStatus());
      }
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
      auditTaskEvent(task, "TASK_REASSIGNED", actor, now);
      return toView(task);
    }

    if ("force-complete".equals(normalizedAction)) {
      BusinessOutcome forceOutcome =
          (request != null && request.outcome() != null && !request.outcome().isBlank())
              ? BusinessOutcome.of(request.outcome().trim().toUpperCase(Locale.ROOT))
              : BusinessOutcome.SUCCESS;
      String reason =
          (request != null && request.comment() != null && !request.comment().isBlank())
              ? request.comment().trim()
              : null;
      var decisionResult =
          taskCommandService.forceCompleteTask(
              taskId,
              forceOutcome,
              reason,
              request != null ? request.formData() : null,
              correlationId,
              commandId);
      return toView(decisionResult.task());
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

  /**
   * Pre-flight authorization check used before optimistic-concurrency verification so an
   * unauthorized caller cannot probe a task's existence or current version. Mirrors the
   * assignment/candidate rules enforced during execution; never mutates task state.
   */
  public void requireTaskActionAuthorized(TaskExecution task, String action) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(action, "action");
    ActorContext actor = actorContextProvider.requireActor();
    String normalizedAction = action.trim().toLowerCase(Locale.ROOT);
    boolean isPrivileged = actor.hasRole(RoleKey.ADMIN) || actor.hasRole(RoleKey.OPERATOR);

    if (task.getStatus() == TaskStatus.COMPLETED
        || task.getStatus() == TaskStatus.CANCELLED
        || task.getStatus() == TaskStatus.EXPIRED) {
      // Terminal state is not an authorization signal; defer to execution-time state guard.
      return;
    }

    if ("claim".equals(normalizedAction)) {
      if (task.getAssigneeId() != null
          && !task.getAssigneeId().equals(actor.actorId())
          && !isPrivileged) {
        throw new AccessDeniedException("Task is assigned to a different user: " + task.getId());
      }
      if (candidateRepository != null && task.getAssigneeId() == null) {
        List<com.fpt.workflow.task.domain.TaskCandidate> candidates =
            candidateRepository.findAllByTaskIdOrderByCreatedAtAsc(task.getId());
        boolean hasClaimable = candidates.stream().anyMatch(TaskQueryService::isClaimableCandidate);
        if (hasClaimable && !isPrivileged) {
          boolean isCandidate =
              candidates.stream().anyMatch(c -> c.getUserId().equals(actor.actorId()));
          if (!isCandidate) {
            throw new AccessDeniedException(
                "Actor is not an eligible candidate for task: " + task.getId());
          }
        }
      }
      return;
    }

    if ("unclaim".equals(normalizedAction)) {
      if (!isPrivileged
          && (task.getAssigneeId() == null || !task.getAssigneeId().equals(actor.actorId()))) {
        throw new AccessDeniedException("Not authorized to unclaim task: " + task.getId());
      }
      return;
    }

    if ("reassign".equals(normalizedAction)) {
      if (!isPrivileged
          && task.getAssigneeId() != null
          && !task.getAssigneeId().equals(actor.actorId())) {
        throw new AccessDeniedException("Not authorized to reassign task: " + task.getId());
      }
      return;
    }

    if ("force-complete".equals(normalizedAction)) {
      if (!isPrivileged) {
        throw new AccessDeniedException("Actor is not authorized to force complete task");
      }
      return;
    }

    // Decide-style actions (approve/reject/complete/request-revision/…): assignee-only.
    if (task.getAssigneeId() != null && !task.getAssigneeId().equals(actor.actorId())) {
      throw new AccessDeniedException(
          "Task is assigned to a different user: " + task.getId());
    }
  }

  /** P2-16 (§24.1): task lifecycle audit (claim/unclaim/reassign). */
  private void auditTaskEvent(
      com.fpt.workflow.task.domain.TaskExecution task,
      String eventType,
      com.fpt.workflow.security.ActorContext actor,
      java.time.Instant now) {
    if (auditRepository == null) {
      return;
    }
    com.fasterxml.jackson.databind.node.ObjectNode metadata =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    metadata.put("taskId", task.getId().toString());
    metadata.put("nodeExecutionId", task.getNodeExecutionId().toString());
    metadata.put("status", task.getStatus().name());
    auditRepository.save(
        com.fpt.workflow.operations.audit.AuditEvent.record(
            uuidGenerator.generate(),
            "TASK_EXECUTION",
            task.getId(),
            eventType,
            actor.actorId(),
            actor.actorId(),
            new com.fpt.workflow.shared.domain.CorrelationId(
                java.util.UUID.nameUUIDFromBytes(
                    ("audit:" + task.getId() + ":" + eventType)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            new com.fpt.workflow.shared.domain.CommandId(
                java.util.UUID.nameUUIDFromBytes(
                    ("audit:" + task.getId() + ":" + eventType + ":" + now)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            metadata,
            now));
  }

  private static boolean isClaimableCandidate(
      com.fpt.workflow.task.domain.TaskCandidate candidate) {
    return !"SEQUENTIAL".equalsIgnoreCase(candidate.getSourceType());
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
