package com.fpt.workflow.operations.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Scoped context manager for structured logging MDC entries across workflow lifecycle. Manages
 * standard MDC keys: requestId, correlationId, eventId, nodeExecutionId, taskId, commandId,
 * integrationExecutionId.
 *
 * <p>Implements AutoCloseable to guarantee proper cleanup and restore previous MDC state,
 * preventing cross-request or cross-thread MDC pollution.
 */
public final class WorkflowMdcScope implements AutoCloseable {

  public static final String KEY_REQUEST_ID = "requestId";
  public static final String KEY_CORRELATION_ID = "correlationId";
  public static final String KEY_EVENT_ID = "eventId";
  public static final String KEY_NODE_EXECUTION_ID = "nodeExecutionId";
  public static final String KEY_TASK_ID = "taskId";
  public static final String KEY_COMMAND_ID = "commandId";
  public static final String KEY_INTEGRATION_EXECUTION_ID = "integrationExecutionId";

  private final Map<String, String> previousValues = new HashMap<>();

  private WorkflowMdcScope() {}

  public static Builder builder() {
    return new Builder();
  }

  public static WorkflowMdcScope forEvent(UUID eventId) {
    return builder().eventId(eventId).open();
  }

  public static WorkflowMdcScope forNodeExecution(UUID eventId, UUID nodeExecutionId) {
    return builder().eventId(eventId).nodeExecutionId(nodeExecutionId).open();
  }

  public static WorkflowMdcScope forTask(UUID eventId, UUID taskId) {
    return builder().eventId(eventId).taskId(taskId).open();
  }

  public static WorkflowMdcScope forIntegration(UUID eventId, UUID integrationExecutionId) {
    return builder().eventId(eventId).integrationExecutionId(integrationExecutionId).open();
  }

  public static WorkflowMdcScope forCommand(UUID commandId) {
    return builder().commandId(commandId).open();
  }

  private void put(String key, String value) {
    if (value == null) {
      return;
    }
    previousValues.put(key, MDC.get(key));
    String sanitized = SensitiveDataMasker.maskIfSensitive(key, value);
    MDC.put(key, sanitized);
  }

  @Override
  public void close() {
    for (Map.Entry<String, String> entry : previousValues.entrySet()) {
      if (entry.getValue() == null) {
        MDC.remove(entry.getKey());
      } else {
        MDC.put(entry.getKey(), entry.getValue());
      }
    }
  }

  public static final class Builder {
    private String requestId;
    private String correlationId;
    private String eventId;
    private String nodeExecutionId;
    private String taskId;
    private String commandId;
    private String integrationExecutionId;

    public Builder requestId(String requestId) {
      this.requestId = requestId;
      return this;
    }

    public Builder requestId(UUID requestId) {
      this.requestId = requestId != null ? requestId.toString() : null;
      return this;
    }

    public Builder correlationId(String correlationId) {
      this.correlationId = correlationId;
      return this;
    }

    public Builder correlationId(UUID correlationId) {
      this.correlationId = correlationId != null ? correlationId.toString() : null;
      return this;
    }

    public Builder eventId(String eventId) {
      this.eventId = eventId;
      return this;
    }

    public Builder eventId(UUID eventId) {
      this.eventId = eventId != null ? eventId.toString() : null;
      return this;
    }

    public Builder nodeExecutionId(String nodeExecutionId) {
      this.nodeExecutionId = nodeExecutionId;
      return this;
    }

    public Builder nodeExecutionId(UUID nodeExecutionId) {
      this.nodeExecutionId = nodeExecutionId != null ? nodeExecutionId.toString() : null;
      return this;
    }

    public Builder taskId(String taskId) {
      this.taskId = taskId;
      return this;
    }

    public Builder taskId(UUID taskId) {
      this.taskId = taskId != null ? taskId.toString() : null;
      return this;
    }

    public Builder commandId(String commandId) {
      this.commandId = commandId;
      return this;
    }

    public Builder commandId(UUID commandId) {
      this.commandId = commandId != null ? commandId.toString() : null;
      return this;
    }

    public Builder integrationExecutionId(String integrationExecutionId) {
      this.integrationExecutionId = integrationExecutionId;
      return this;
    }

    public Builder integrationExecutionId(UUID integrationExecutionId) {
      this.integrationExecutionId =
          integrationExecutionId != null ? integrationExecutionId.toString() : null;
      return this;
    }

    public WorkflowMdcScope open() {
      WorkflowMdcScope scope = new WorkflowMdcScope();
      scope.put(KEY_REQUEST_ID, requestId);
      scope.put(KEY_CORRELATION_ID, correlationId);
      scope.put(KEY_EVENT_ID, eventId);
      scope.put(KEY_NODE_EXECUTION_ID, nodeExecutionId);
      scope.put(KEY_TASK_ID, taskId);
      scope.put(KEY_COMMAND_ID, commandId);
      scope.put(KEY_INTEGRATION_EXECUTION_ID, integrationExecutionId);
      return scope;
    }
  }
}
