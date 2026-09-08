package com.fpt.workflow.operations.job;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_jobs")
public class WorkflowJob {
  @Id private UUID id;

  @Column(name = "job_type", nullable = false, length = 128)
  private String jobType;

  @Column(name = "aggregate_type", nullable = false, length = 128)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode payloadJson;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private WorkflowJobStatus status;

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

  @Column(name = "dedup_key", nullable = false, length = 512)
  private String dedupKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "last_error_json", columnDefinition = "jsonb")
  private JsonNode lastErrorJson;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Column(name = "completed_at", columnDefinition = "timestamptz")
  private Instant completedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected WorkflowJob() {}

  public UUID getId() {
    return id;
  }

  public String getJobType() {
    return jobType;
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

  public WorkflowJobStatus getStatus() {
    return status;
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

  public String getDedupKey() {
    return dedupKey;
  }

  public JsonNode getLastErrorJson() {
    return lastErrorJson == null ? null : lastErrorJson.deepCopy();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }
}
