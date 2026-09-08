package com.fpt.workflow.runtime.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.TransitionGuard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "events")
public class Event {

  @Id private UUID id;

  @Column(name = "ticket_id", nullable = false)
  private UUID ticketId;

  @Column(name = "workflow_version_id", nullable = false)
  private UUID workflowVersionId;

  @Column(name = "started_ticket_revision_id", nullable = false)
  private UUID startedTicketRevisionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32)
  private EventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private EventStatus status;

  @Column(length = 128)
  private String outcome;

  @Enumerated(EnumType.STRING)
  @Column(name = "wait_reason", length = 64)
  private RuntimeWaitReason waitReason;

  @Column(name = "root_event_id", nullable = false)
  private UUID rootEventId;

  @Column(name = "parent_event_id")
  private UUID parentEventId;

  @Column(name = "parent_node_execution_id")
  private UUID parentNodeExecutionId;

  @Column(name = "previous_event_id")
  private UUID previousEventId;

  @Column(name = "restarted_from_event_id")
  private UUID restartedFromEventId;

  @Column(name = "trigger_type", nullable = false, length = 128)
  private String triggerType;

  @Column(name = "trigger_correlation_key", length = 512)
  private String triggerCorrelationKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "variables_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode variablesJson;

  @Column(name = "started_by", nullable = false)
  private UUID startedBy;

  @Column(name = "started_at", nullable = false, columnDefinition = "timestamptz")
  private Instant startedAt;

  @Column(name = "ended_at", columnDefinition = "timestamptz")
  private Instant endedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected Event() {}

  private Event(
      UUID id,
      UUID ticketId,
      UUID workflowVersionId,
      UUID startedTicketRevisionId,
      EventType eventType,
      UUID rootEventId,
      UUID parentEventId,
      UUID parentNodeExecutionId,
      UUID previousEventId,
      UUID restartedFromEventId,
      String triggerType,
      String triggerCorrelationKey,
      JsonNode variablesJson,
      UUID startedBy,
      Instant startedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.workflowVersionId = Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    this.startedTicketRevisionId =
        Objects.requireNonNull(startedTicketRevisionId, "startedTicketRevisionId");
    this.eventType = Objects.requireNonNull(eventType, "eventType");
    this.rootEventId = Objects.requireNonNull(rootEventId, "rootEventId");
    this.parentEventId = parentEventId;
    this.parentNodeExecutionId = parentNodeExecutionId;
    validateHierarchy();
    if (id.equals(previousEventId) || id.equals(restartedFromEventId)) {
      throw new IllegalArgumentException("Event history cannot reference itself");
    }
    this.previousEventId = previousEventId;
    this.restartedFromEventId = restartedFromEventId;
    this.triggerType = RuntimeValues.key(triggerType, "triggerType");
    this.triggerCorrelationKey =
        RuntimeValues.optionalText(triggerCorrelationKey, "triggerCorrelationKey");
    this.variablesJson = RuntimeValues.object(variablesJson, "variablesJson");
    this.startedBy = Objects.requireNonNull(startedBy, "startedBy");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    this.status = EventStatus.CREATED;
  }

  public static Event createRoot(
      UUID id,
      UUID ticketId,
      UUID workflowVersionId,
      UUID startedTicketRevisionId,
      UUID previousEventId,
      UUID restartedFromEventId,
      String triggerType,
      String triggerCorrelationKey,
      JsonNode variablesJson,
      UUID startedBy,
      Instant startedAt) {
    return new Event(
        id,
        ticketId,
        workflowVersionId,
        startedTicketRevisionId,
        EventType.ROOT,
        id,
        null,
        null,
        previousEventId,
        restartedFromEventId,
        triggerType,
        triggerCorrelationKey,
        variablesJson,
        startedBy,
        startedAt);
  }

  public static Event createChild(
      UUID id,
      UUID ticketId,
      UUID workflowVersionId,
      UUID startedTicketRevisionId,
      UUID rootEventId,
      UUID parentEventId,
      UUID parentNodeExecutionId,
      String triggerType,
      String triggerCorrelationKey,
      JsonNode variablesJson,
      UUID startedBy,
      Instant startedAt) {
    return new Event(
        id,
        ticketId,
        workflowVersionId,
        startedTicketRevisionId,
        EventType.CHILD,
        rootEventId,
        parentEventId,
        parentNodeExecutionId,
        null,
        null,
        triggerType,
        triggerCorrelationKey,
        variablesJson,
        startedBy,
        startedAt);
  }

  public void markRunning() {
    transition(EventStatus.RUNNING, null, null, null);
  }

  public void waitFor(RuntimeWaitReason reason) {
    transition(EventStatus.WAITING, null, Objects.requireNonNull(reason, "reason"), null);
  }

  public void changeWaitReason(RuntimeWaitReason reason) {
    if (status != EventStatus.WAITING) {
      throw new IllegalStateException("Only a waiting Event can change wait reason");
    }
    this.waitReason = Objects.requireNonNull(reason, "reason");
  }

  public void complete(String outcome, Instant endedAt) {
    transition(
        EventStatus.COMPLETED,
        RuntimeValues.key(outcome, "outcome"),
        null,
        RuntimeValues.notBefore(endedAt, startedAt, "endedAt"));
  }

  public void fail(Instant endedAt) {
    transition(
        EventStatus.FAILED, null, null, RuntimeValues.notBefore(endedAt, startedAt, "endedAt"));
  }

  public void cancel(String outcome, Instant endedAt) {
    transition(
        EventStatus.CANCELLED,
        outcome == null ? null : RuntimeValues.key(outcome, "outcome"),
        null,
        RuntimeValues.notBefore(endedAt, startedAt, "endedAt"));
  }

  public void terminate(String outcome, Instant endedAt) {
    transition(
        EventStatus.TERMINATED,
        outcome == null ? null : RuntimeValues.key(outcome, "outcome"),
        null,
        RuntimeValues.notBefore(endedAt, startedAt, "endedAt"));
  }

  private void transition(
      EventStatus target, String outcome, RuntimeWaitReason waitReason, Instant endedAt) {
    TransitionGuard.requireAllowed(status, target, allowedTargets(status));
    this.status = target;
    this.outcome = outcome;
    this.waitReason = waitReason;
    this.endedAt = endedAt;
  }

  private List<EventStatus> allowedTargets(EventStatus current) {
    return switch (current) {
      case CREATED ->
          List.of(
              EventStatus.RUNNING,
              EventStatus.WAITING,
              EventStatus.FAILED,
              EventStatus.CANCELLED,
              EventStatus.TERMINATED);
      case RUNNING, WAITING ->
          List.of(
              EventStatus.RUNNING,
              EventStatus.WAITING,
              EventStatus.COMPLETED,
              EventStatus.FAILED,
              EventStatus.CANCELLED,
              EventStatus.TERMINATED);
      case COMPLETED, FAILED, CANCELLED, TERMINATED -> List.of();
    };
  }

  private void validateHierarchy() {
    if (eventType == EventType.ROOT) {
      if (!id.equals(rootEventId) || parentEventId != null || parentNodeExecutionId != null) {
        throw new IllegalArgumentException("A root Event must be its own root without a parent");
      }
      return;
    }
    if (id.equals(rootEventId) || parentEventId == null || parentNodeExecutionId == null) {
      throw new IllegalArgumentException(
          "A child Event requires root, parent Event, and parent node");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getWorkflowVersionId() {
    return workflowVersionId;
  }

  public UUID getStartedTicketRevisionId() {
    return startedTicketRevisionId;
  }

  public EventType getEventType() {
    return eventType;
  }

  public EventStatus getStatus() {
    return status;
  }

  public String getOutcome() {
    return outcome;
  }

  public RuntimeWaitReason getWaitReason() {
    return waitReason;
  }

  public UUID getRootEventId() {
    return rootEventId;
  }

  public UUID getParentEventId() {
    return parentEventId;
  }

  public UUID getParentNodeExecutionId() {
    return parentNodeExecutionId;
  }

  public UUID getPreviousEventId() {
    return previousEventId;
  }

  public UUID getRestartedFromEventId() {
    return restartedFromEventId;
  }

  public String getTriggerType() {
    return triggerType;
  }

  public String getTriggerCorrelationKey() {
    return triggerCorrelationKey;
  }

  public JsonNode getVariablesJson() {
    return variablesJson.deepCopy();
  }

  /** Applies values already checked against this Event's bound variable declarations. */
  public void applyDeclaredVariableMappings(JsonNode mappings) {
    JsonNode checked = RuntimeValues.object(mappings, "mappings");
    ObjectNode merged = (ObjectNode) variablesJson.deepCopy();
    checked
        .fields()
        .forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue().deepCopy()));
    variablesJson = merged;
  }

  public UUID getStartedBy() {
    return startedBy;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
