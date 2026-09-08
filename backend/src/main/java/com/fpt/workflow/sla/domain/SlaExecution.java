package com.fpt.workflow.sla.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sla_executions")
public class SlaExecution {
  @Id private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "node_execution_id")
  private UUID nodeExecutionId;

  @Column(name = "task_id")
  private UUID taskId;

  @Column(name = "business_calendar_id")
  private UUID businessCalendarId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode configSnapshotJson;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "started_at", nullable = false, columnDefinition = "timestamptz")
  private Instant startedAt;

  @Column(name = "due_at", nullable = false, columnDefinition = "timestamptz")
  private Instant dueAt;

  @Column(name = "breached_at", columnDefinition = "timestamptz")
  private Instant breachedAt;

  @Column(name = "completed_at", columnDefinition = "timestamptz")
  private Instant completedAt;

  @Column(name = "next_action_at", columnDefinition = "timestamptz")
  private Instant nextActionAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected SlaExecution() {}

  public static SlaExecution start(
      UUID id,
      UUID event,
      UUID node,
      UUID task,
      UUID calendar,
      JsonNode config,
      Instant started,
      Instant due,
      Instant next) {
    if (node == null && task == null) throw new IllegalArgumentException("SLA owner required");
    if (due.isBefore(started)) throw new IllegalArgumentException("dueAt before startedAt");
    SlaExecution s = new SlaExecution();
    s.id = id;
    s.eventId = event;
    s.nodeExecutionId = node;
    s.taskId = task;
    s.businessCalendarId = calendar;
    s.configSnapshotJson = config.deepCopy();
    s.status = "ACTIVE";
    s.startedAt = started;
    s.dueAt = due;
    s.nextActionAt = next;
    return s;
  }

  public synchronized boolean breach(Instant at) {
    if (!"ACTIVE".equals(status)) return false;
    status = "BREACHED";
    breachedAt = at;
    return true;
  }

  public void complete(Instant at) {
    if ("ACTIVE".equals(status) || "BREACHED".equals(status)) {
      status = "COMPLETED";
      completedAt = at;
      nextActionAt = null;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public Instant getDueAt() {
    return dueAt;
  }

  public Instant getNextActionAt() {
    return nextActionAt;
  }

  public String getStatus() {
    return status;
  }

  public JsonNode getConfigSnapshotJson() {
    return configSnapshotJson.deepCopy();
  }
}
