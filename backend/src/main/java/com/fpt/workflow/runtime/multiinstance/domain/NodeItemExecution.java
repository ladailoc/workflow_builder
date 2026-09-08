package com.fpt.workflow.runtime.multiinstance.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Execution record for a single item within a multi-instance node execution. Enforces
 * UNIQUE(parent_node_execution_id, item_index).
 */
@Entity
@Table(name = "node_item_executions")
public class NodeItemExecution {

  @Id private UUID id;

  @Column(name = "multi_instance_state_id", nullable = false)
  private UUID multiInstanceStateId;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "parent_node_execution_id", nullable = false)
  private UUID parentNodeExecutionId;

  @Column(name = "item_index", nullable = false)
  private int itemIndex;

  @Column(name = "item_token", nullable = false, length = 256)
  private String itemToken;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "item_data_json", columnDefinition = "jsonb")
  private JsonNode itemDataJson;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "outcome_port", length = 128)
  private String outcomePort;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "output_json", columnDefinition = "jsonb")
  private JsonNode outputJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "error_json", columnDefinition = "jsonb")
  private JsonNode errorJson;

  @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
  private Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected NodeItemExecution() {}

  private NodeItemExecution(
      UUID id,
      UUID multiInstanceStateId,
      UUID eventId,
      UUID parentNodeExecutionId,
      int itemIndex,
      String itemToken,
      JsonNode itemDataJson,
      Instant now) {
    this.id = Objects.requireNonNull(id, "id");
    this.multiInstanceStateId =
        Objects.requireNonNull(multiInstanceStateId, "multiInstanceStateId");
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.parentNodeExecutionId =
        Objects.requireNonNull(parentNodeExecutionId, "parentNodeExecutionId");
    if (itemIndex < 0) {
      throw new IllegalArgumentException("itemIndex must not be negative");
    }
    this.itemIndex = itemIndex;
    this.itemToken = Objects.requireNonNull(itemToken, "itemToken");
    this.itemDataJson = itemDataJson;
    this.status = "PENDING";
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  public static NodeItemExecution create(
      UUID id,
      UUID multiInstanceStateId,
      UUID eventId,
      UUID parentNodeExecutionId,
      int itemIndex,
      String itemToken,
      JsonNode itemDataJson,
      Instant now) {
    return new NodeItemExecution(
        id,
        multiInstanceStateId,
        eventId,
        parentNodeExecutionId,
        itemIndex,
        itemToken,
        itemDataJson,
        now);
  }

  public void markRunning(Instant now) {
    this.status = "RUNNING";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void complete(String outcomePort, JsonNode outputJson, Instant now) {
    this.status = "COMPLETED";
    this.outcomePort = outcomePort;
    this.outputJson = outputJson;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void fail(JsonNode errorJson, Instant now) {
    this.status = "FAILED";
    this.errorJson = errorJson;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void cancel(Instant now) {
    if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
      return;
    }
    this.status = "CANCELLED";
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public UUID getId() {
    return id;
  }

  public UUID getMultiInstanceStateId() {
    return multiInstanceStateId;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getParentNodeExecutionId() {
    return parentNodeExecutionId;
  }

  public int getItemIndex() {
    return itemIndex;
  }

  public String getItemToken() {
    return itemToken;
  }

  public JsonNode getItemDataJson() {
    return itemDataJson;
  }

  public String getStatus() {
    return status;
  }

  public String getOutcomePort() {
    return outcomePort;
  }

  public JsonNode getOutputJson() {
    return outputJson;
  }

  public JsonNode getErrorJson() {
    return errorJson;
  }
}
