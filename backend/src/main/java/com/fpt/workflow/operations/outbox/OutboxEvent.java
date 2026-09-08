package com.fpt.workflow.operations.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
  @Id private UUID id;

  @Column(name = "event_type", nullable = false, length = 128)
  private String eventType;

  @Column(name = "aggregate_type", nullable = false, length = 128)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode payloadJson;

  @Column(name = "dedup_key", nullable = false, length = 512)
  private String dedupKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private OutboxStatus status;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "published_at", columnDefinition = "timestamptz")
  private Instant publishedAt;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "next_run_at", nullable = false, columnDefinition = "timestamptz")
  private Instant nextRunAt;

  @Column(name = "lease_owner")
  private String leaseOwner;

  @Column(name = "lease_until", columnDefinition = "timestamptz")
  private Instant leaseUntil;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "last_error_json", columnDefinition = "jsonb")
  private JsonNode lastErrorJson;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected OutboxEvent() {}

  public UUID getId() {
    return id;
  }

  public String getEventType() {
    return eventType;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public JsonNode getPayloadJson() {
    return payloadJson.deepCopy();
  }

  public String getDedupKey() {
    return dedupKey;
  }

  public OutboxStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public int getAttempts() {
    return attempts;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public Instant getNextRunAt() {
    return nextRunAt;
  }

  public String getLeaseOwner() {
    return leaseOwner;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public JsonNode getLastErrorJson() {
    return lastErrorJson == null ? null : lastErrorJson.deepCopy();
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
