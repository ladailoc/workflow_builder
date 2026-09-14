package com.fpt.workflow.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.operations.command.CommandCompletion;
import com.fpt.workflow.operations.command.CommandExecutor;
import com.fpt.workflow.operations.command.CommandInvocation;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.dto.TaskDtos;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Wraps task mutating operations in persistent command execution for idempotency,
 * optimistic concurrency version check, and audit logging.
 */
@Service
public class TaskCommandFacade {

  private final CommandExecutor commandExecutor;
  private final TaskQueryService taskQueryService;
  private final TaskExecutionRepository taskRepository;
  private final ObjectMapper objectMapper;
  private final CanonicalDefinitionJson canonicalJson;
  private final TaskRevisionRequestPort revisionRequestService;

  @org.springframework.beans.factory.annotation.Autowired
  public TaskCommandFacade(
      CommandExecutor commandExecutor,
      TaskQueryService taskQueryService,
      TaskExecutionRepository taskRepository,
      ObjectMapper objectMapper,
      CanonicalDefinitionJson canonicalJson,
      TaskRevisionRequestPort revisionRequestService) {
    this.commandExecutor = commandExecutor;
    this.taskQueryService = taskQueryService;
    this.taskRepository = taskRepository;
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
    this.revisionRequestService = revisionRequestService;
  }

  public TaskCommandFacade(
      CommandExecutor commandExecutor,
      TaskQueryService taskQueryService,
      TaskExecutionRepository taskRepository,
      ObjectMapper objectMapper,
      CanonicalDefinitionJson canonicalJson) {
    this(commandExecutor, taskQueryService, taskRepository, objectMapper, canonicalJson, null);
  }

  public TaskDtos.TaskItemView executeAction(
      UUID taskId,
      String action,
      ExpectedVersion expectedVersion,
      TaskDtos.TaskActionRequest request,
      CommandId commandId,
      CorrelationId correlationId) {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(commandId, "commandId");

    String commandType = "TASK_" + action.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    boolean requestRevision = "TASK_REQUEST_REVISION".equals(commandType);
    if (requestRevision && expectedVersion == null) {
      throw new IllegalArgumentException("If-Match is required for REQUEST_REVISION");
    }

    return execute(
        new CommandInvocation(
            "TASK",
            taskId,
            commandId,
            commandType,
            expectedVersion != null ? expectedVersion.value() : null,
            hash(
                request != null
                    ? request
                    : new TaskDtos.TaskActionRequest(null, null, null, null, null))),
        () -> {
          if (expectedVersion != null) {
            TaskExecution task =
                taskRepository
                    .findById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            // Authorization (visibility/assignment) is verified BEFORE the version check so an
            // unauthorized caller cannot probe a task's existence or current version through a
            // 409 STALE_EXPECTED_VERSION response.
            taskQueryService.requireTaskActionAuthorized(task, action);
            if (task.getLockVersion() != expectedVersion.value()) {
              throw new com.fpt.workflow.shared.api.CommandConflictException(
                  "STALE_EXPECTED_VERSION",
                  "Task lock version does not match If-Match: expected "
                      + expectedVersion.value()
                      + " but was "
                      + task.getLockVersion());
            }
          }
          if (requestRevision) {
            if (revisionRequestService == null) {
              throw new IllegalStateException("RevisionRequestService is unavailable");
            }
            TaskDtos.TaskActionRequest effectiveRequest =
                request != null
                    ? request
                    : new TaskDtos.TaskActionRequest(null, null, null, null, null);
            revisionRequestService.open(
                taskId,
                effectiveRequest.targetNodeId(),
                effectiveRequest.comment(),
                requestedFields(effectiveRequest.requestedFields()),
                correlationId,
                commandId);
            return taskQueryService.toView(
                taskRepository
                    .findById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId)));
          }
          return taskQueryService.executeAction(taskId, action, request, commandId, correlationId);
        });
  }

  private List<TaskRevisionRequestPort.FieldSpec> requestedFields(JsonNode requestedFields) {
    if (requestedFields == null || requestedFields.isNull()) {
      return List.of();
    }
    if (!requestedFields.isArray()) {
      throw new IllegalArgumentException("requestedFields must be an array");
    }
    List<TaskRevisionRequestPort.FieldSpec> result = new ArrayList<>();
    for (JsonNode field : requestedFields) {
      if (!field.isObject()) {
        throw new IllegalArgumentException("Each requested field must be an object");
      }
      String key = requiredText(field, "key");
      String label = requiredText(field, "label");
      String type = requiredText(field, "type").toUpperCase(Locale.ROOT);
      if (!SUPPORTED_REVISION_FIELD_TYPES.contains(type)) {
        throw new IllegalArgumentException("Unsupported runtime requested field type");
      }
      JsonNode schema =
          field.path("schema").isObject()
              ? field.path("schema").deepCopy()
              : objectMapper.createObjectNode();
      result.add(
          new TaskRevisionRequestPort.FieldSpec(
              key,
              label,
              type,
              field.path("required").asBoolean(false),
              field.path("sensitive").asBoolean(false),
              schema));
    }
    return List.copyOf(result);
  }

  private static final java.util.Set<String> SUPPORTED_REVISION_FIELD_TYPES =
      java.util.Set.of(
          "TEXT", "TEXTAREA", "NUMBER", "DATE", "DATETIME", "SELECT", "BOOLEAN", "FILE", "FILE_LIST");

  private String requiredText(JsonNode object, String field) {
    String value = object.path(field).asText("").trim();
    if (value.isBlank()) {
      throw new IllegalArgumentException("requestedFields." + field + " is required");
    }
    return value;
  }

  private TaskDtos.TaskItemView execute(
      CommandInvocation invocation, java.util.function.Supplier<TaskDtos.TaskItemView> action) {
    var result =
        commandExecutor.execute(
            invocation,
            () ->
                new CommandCompletion(
                    objectMapper.valueToTree(action.get()), objectMapper.createObjectNode()));
    try {
      return objectMapper.treeToValue(result.resultJson(), TaskDtos.TaskItemView.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored Task command result cannot be decoded", exception);
    }
  }

  private String hash(Object request) {
    JsonNode canonical = canonicalJson.canonicalize(objectMapper.valueToTree(request));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }
}
