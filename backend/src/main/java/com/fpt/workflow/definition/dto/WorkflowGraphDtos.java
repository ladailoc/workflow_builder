package com.fpt.workflow.definition.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import java.util.List;
import java.util.UUID;

public final class WorkflowGraphDtos {

  private WorkflowGraphDtos() {}

  private static JsonNode nullableJson(JsonNode value) {
    return value == null || value.isNull() ? null : value;
  }

  public record CreateNode(
      UUID workflowVersionId,
      String nodeKey,
      String nodeType,
      String name,
      String description,
      int configSchemaVersion,
      JsonNode configJson,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode positionJson) {
    public CreateNode {
      inputSchemaJson = nullableJson(inputSchemaJson);
      outputSchemaJson = nullableJson(outputSchemaJson);
    }
  }

  public record UpdateNode(
      String nodeType,
      String name,
      String description,
      int configSchemaVersion,
      JsonNode configJson,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode positionJson) {
    public UpdateNode {
      inputSchemaJson = nullableJson(inputSchemaJson);
      outputSchemaJson = nullableJson(outputSchemaJson);
    }
  }

  public record NodeView(
      UUID id,
      UUID workflowVersionId,
      String nodeKey,
      String nodeType,
      String name,
      String description,
      int configSchemaVersion,
      JsonNode configJson,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode positionJson) {

    public static NodeView from(NodeDefinition node) {
      return new NodeView(
          node.getId(),
          node.getWorkflowVersionId(),
          node.getNodeKey(),
          node.getNodeType(),
          node.getName(),
          node.getDescription(),
          node.getConfigSchemaVersion(),
          node.getConfigJson(),
          node.getInputSchemaJson(),
          node.getOutputSchemaJson(),
          node.getPositionJson());
    }
  }

  public record CreateEdge(
      UUID workflowVersionId,
      UUID sourceNodeId,
      String sourcePort,
      UUID targetNodeId,
      JsonNode conditionJson,
      int priority,
      boolean defaultTransition,
      TransitionType transitionType,
      String label,
      JsonNode configJson) {
    public CreateEdge {
      conditionJson = nullableJson(conditionJson);
    }
  }

  public record UpdateEdge(
      UUID sourceNodeId,
      String sourcePort,
      UUID targetNodeId,
      JsonNode conditionJson,
      int priority,
      boolean defaultTransition,
      TransitionType transitionType,
      String label,
      JsonNode configJson) {
    public UpdateEdge {
      conditionJson = nullableJson(conditionJson);
    }
  }

  public record EdgeView(
      UUID id,
      UUID workflowVersionId,
      UUID sourceNodeId,
      String sourcePort,
      UUID targetNodeId,
      JsonNode conditionJson,
      int priority,
      boolean defaultTransition,
      TransitionType transitionType,
      String label,
      JsonNode configJson) {

    public static EdgeView from(EdgeDefinition edge) {
      return new EdgeView(
          edge.getId(),
          edge.getWorkflowVersionId(),
          edge.getSourceNodeId(),
          edge.getSourcePort(),
          edge.getTargetNodeId(),
          edge.getConditionJson(),
          edge.getPriority(),
          edge.isDefaultTransition(),
          edge.getTransitionType(),
          edge.getLabel(),
          edge.getConfigJson());
    }
  }

  public record DraftState(UUID workflowVersionId, long revision, long lockVersion) {

    public static DraftState from(WorkflowVersion version) {
      return new DraftState(version.getId(), version.getRevision(), version.getLockVersion());
    }
  }

  public record NodeMutation(NodeView node, DraftState draft) {}

  public record EdgeMutation(EdgeView edge, DraftState draft) {}

  /** A complete draft graph document. Client references are local to one save request. */
  public record ReplaceGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
    public ReplaceGraph {
      nodes = List.copyOf(nodes == null ? List.of() : nodes);
      edges = List.copyOf(edges == null ? List.of() : edges);
    }
  }

  public record GraphNode(
      String clientRef,
      String nodeKey,
      String nodeType,
      String name,
      String description,
      int configSchemaVersion,
      JsonNode configJson,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode positionJson) {
    public GraphNode {
      configJson = configJson == null ? JsonNodeFactory.instance.objectNode() : configJson;
      inputSchemaJson = nullableJson(inputSchemaJson);
      outputSchemaJson = nullableJson(outputSchemaJson);
      positionJson = positionJson == null ? JsonNodeFactory.instance.objectNode() : positionJson;
    }
  }

  public record GraphEdge(
      String clientRef,
      String sourceClientRef,
      String sourcePort,
      String targetClientRef,
      JsonNode conditionJson,
      int priority,
      boolean defaultTransition,
      TransitionType transitionType,
      String label,
      JsonNode configJson) {
    public GraphEdge {
      conditionJson = nullableJson(conditionJson);
      configJson = configJson == null ? JsonNodeFactory.instance.objectNode() : configJson;
    }
  }

  public record GraphView(List<NodeView> nodes, List<EdgeView> edges, DraftState draft) {
    public GraphView {
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
    }
  }
}
