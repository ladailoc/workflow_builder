package com.fpt.workflow.definition.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import java.util.UUID;

public final class WorkflowGraphDtos {

  private WorkflowGraphDtos() {}

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
      JsonNode positionJson) {}

  public record UpdateNode(
      String nodeType,
      String name,
      String description,
      int configSchemaVersion,
      JsonNode configJson,
      JsonNode inputSchemaJson,
      JsonNode outputSchemaJson,
      JsonNode positionJson) {}

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
      JsonNode configJson) {}

  public record UpdateEdge(
      UUID sourceNodeId,
      String sourcePort,
      UUID targetNodeId,
      JsonNode conditionJson,
      int priority,
      boolean defaultTransition,
      TransitionType transitionType,
      String label,
      JsonNode configJson) {}

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
}
