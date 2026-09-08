package com.fpt.workflow.runtime.routing.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable record of every routing evaluation. One per source node execution. Created before any
 * activation token so crash-recovery can replay without re-evaluating conditions.
 */
@Entity
@Table(name = "routing_decisions")
public class RoutingDecision {

  @Id private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "source_node_execution_id", nullable = false, unique = true)
  private UUID sourceNodeExecutionId;

  @Column(name = "outcome_port", nullable = false, length = 128)
  private String outcomePort;

  @Column(name = "routing_mode", nullable = false, length = 64)
  private String routingMode;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evaluated_edges_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode evaluatedEdgesJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "selected_edge_ids_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode selectedEdgeIdsJson;

  @Column(name = "decided_at", nullable = false, columnDefinition = "timestamptz")
  private Instant decidedAt;

  protected RoutingDecision() {}

  private RoutingDecision(
      UUID id,
      UUID eventId,
      UUID sourceNodeExecutionId,
      String outcomePort,
      String routingMode,
      JsonNode evaluatedEdgesJson,
      JsonNode selectedEdgeIdsJson,
      Instant decidedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.eventId = Objects.requireNonNull(eventId, "eventId");
    this.sourceNodeExecutionId =
        Objects.requireNonNull(sourceNodeExecutionId, "sourceNodeExecutionId");
    this.outcomePort = Objects.requireNonNull(outcomePort, "outcomePort");
    this.routingMode = Objects.requireNonNull(routingMode, "routingMode");
    this.evaluatedEdgesJson = Objects.requireNonNull(evaluatedEdgesJson, "evaluatedEdgesJson");
    this.selectedEdgeIdsJson = Objects.requireNonNull(selectedEdgeIdsJson, "selectedEdgeIdsJson");
    this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
  }

  public static RoutingDecision record(
      UUID id,
      UUID eventId,
      UUID sourceNodeExecutionId,
      String outcomePort,
      String routingMode,
      JsonNode evaluatedEdgesJson,
      JsonNode selectedEdgeIdsJson,
      Instant decidedAt) {
    return new RoutingDecision(
        id,
        eventId,
        sourceNodeExecutionId,
        outcomePort,
        routingMode,
        evaluatedEdgesJson,
        selectedEdgeIdsJson,
        decidedAt);
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getSourceNodeExecutionId() {
    return sourceNodeExecutionId;
  }

  public String getOutcomePort() {
    return outcomePort;
  }

  public String getRoutingMode() {
    return routingMode;
  }

  public JsonNode getEvaluatedEdgesJson() {
    return evaluatedEdgesJson;
  }

  public JsonNode getSelectedEdgeIdsJson() {
    return selectedEdgeIdsJson;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }
}
