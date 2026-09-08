package com.fpt.workflow.slanotification.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notification_dispatches")
public class NotificationDispatch {
  @Id private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "node_execution_id")
  private UUID nodeExecutionId;

  @Column(name = "task_id")
  private UUID taskId;

  @Column(nullable = false, length = 64)
  private String channel;

  @Column(name = "recipient_user_id", nullable = false)
  private UUID recipientUserId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "recipient_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode recipientSnapshotJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "template_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode templateSnapshotJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode payloadJson;

  @Column(name = "dedup_key", nullable = false, length = 512)
  private String dedupKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private NotificationDispatchStatus status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "allow_after_terminal", nullable = false)
  private boolean allowAfterTerminal;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Column(name = "sent_at", columnDefinition = "timestamptz")
  private Instant sentAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "last_error_json", columnDefinition = "jsonb")
  private JsonNode lastErrorJson;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected NotificationDispatch() {}

  public void begin(Instant now) {
    if (status != NotificationDispatchStatus.READY && status != NotificationDispatchStatus.FAILED)
      throw new IllegalStateException("Dispatch is not deliverable");
    status = NotificationDispatchStatus.SENDING;
    attempts++;
    updatedAt = Objects.requireNonNull(now);
    lastErrorJson = null;
  }

  public void sent(Instant now) {
    if (status != NotificationDispatchStatus.SENDING)
      throw new IllegalStateException("Dispatch is not sending");
    status = NotificationDispatchStatus.SENT;
    sentAt = now;
    updatedAt = now;
  }

  public void failed(JsonNode error, boolean dead, Instant now) {
    if (status != NotificationDispatchStatus.SENDING)
      throw new IllegalStateException("Dispatch is not sending");
    status = dead ? NotificationDispatchStatus.DEAD : NotificationDispatchStatus.FAILED;
    lastErrorJson = Objects.requireNonNull(error).deepCopy();
    updatedAt = now;
  }

  public void cancel(Instant now) {
    if (status == NotificationDispatchStatus.SENT
        || status == NotificationDispatchStatus.DEAD
        || status == NotificationDispatchStatus.CANCELLED)
      throw new IllegalStateException("Dispatch is terminal");
    status = NotificationDispatchStatus.CANCELLED;
    updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getNodeExecutionId() {
    return nodeExecutionId;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public String getChannel() {
    return channel;
  }

  public UUID getRecipientUserId() {
    return recipientUserId;
  }

  public JsonNode getRecipientSnapshotJson() {
    return recipientSnapshotJson.deepCopy();
  }

  public JsonNode getTemplateSnapshotJson() {
    return templateSnapshotJson.deepCopy();
  }

  public JsonNode getPayloadJson() {
    return payloadJson.deepCopy();
  }

  public String getDedupKey() {
    return dedupKey;
  }

  public NotificationDispatchStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public boolean isAllowAfterTerminal() {
    return allowAfterTerminal;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public JsonNode getLastErrorJson() {
    return lastErrorJson == null ? null : lastErrorJson.deepCopy();
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
