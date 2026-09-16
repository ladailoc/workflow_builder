package com.fpt.workflow.definition.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.StaleDraftRevisionException;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.dto.WorkflowGraphDtos;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class WorkflowGraphService {

  private static final Pattern GRAPH_KEY_PATTERN =
      Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");

  private final WorkflowVersionRepository workflowVersionRepository;
  private final NodeDefinitionRepository nodeDefinitionRepository;
  private final EdgeDefinitionRepository edgeDefinitionRepository;
  private final NodeTypeRegistry nodeTypeRegistry;
  private final UuidGenerator uuidGenerator;

  public WorkflowGraphService(
      WorkflowVersionRepository workflowVersionRepository,
      NodeDefinitionRepository nodeDefinitionRepository,
      EdgeDefinitionRepository edgeDefinitionRepository,
      NodeTypeRegistry nodeTypeRegistry,
      UuidGenerator uuidGenerator) {
    this.workflowVersionRepository = workflowVersionRepository;
    this.nodeDefinitionRepository = nodeDefinitionRepository;
    this.edgeDefinitionRepository = edgeDefinitionRepository;
    this.nodeTypeRegistry = nodeTypeRegistry;
    this.uuidGenerator = uuidGenerator;
  }

  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  @TransactionalCommand
  public WorkflowGraphDtos.NodeMutation createNode(
      WorkflowGraphDtos.CreateNode request,
      ExpectedVersion expectedVersion,
      long expectedRevision) {
    WorkflowVersion version =
        beginGraphMutation(request.workflowVersionId(), expectedVersion, expectedRevision);
    if (nodeDefinitionRepository.existsByWorkflowVersionIdAndNodeKey(
        request.workflowVersionId(), request.nodeKey())) {
      throw new CommandConflictException(
          "WORKFLOW_NODE_KEY_CONFLICT", "Node key already exists in this WorkflowVersion");
    }
    validateNodeManifest(
        request.nodeKey(), request.nodeType(), request.configSchemaVersion(), request.configJson());

    NodeDefinition node =
        NodeDefinition.create(
            uuidGenerator.generate(),
            request.workflowVersionId(),
            request.nodeKey(),
            request.nodeType(),
            request.name(),
            request.description(),
            request.configSchemaVersion(),
            request.configJson(),
            request.inputSchemaJson(),
            request.outputSchemaJson(),
            request.positionJson());
    NodeDefinition savedNode = nodeDefinitionRepository.save(node);
    WorkflowVersion savedVersion = workflowVersionRepository.saveAndFlush(version);
    return new WorkflowGraphDtos.NodeMutation(
        WorkflowGraphDtos.NodeView.from(savedNode),
        WorkflowGraphDtos.DraftState.from(savedVersion));
  }

  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  @TransactionalCommand
  public WorkflowGraphDtos.NodeMutation updateNode(
      UUID nodeId,
      WorkflowGraphDtos.UpdateNode request,
      ExpectedVersion expectedVersion,
      long expectedRevision) {
    NodeDefinition node = requireNode(nodeId);
    WorkflowVersion version =
        beginGraphMutation(node.getWorkflowVersionId(), expectedVersion, expectedRevision);
    validateNodeManifest(
        node.getNodeKey(), request.nodeType(), request.configSchemaVersion(), request.configJson());
    node.update(
        request.nodeType(),
        request.name(),
        request.description(),
        request.configSchemaVersion(),
        request.configJson(),
        request.inputSchemaJson(),
        request.outputSchemaJson(),
        request.positionJson());
    NodeDefinition savedNode = nodeDefinitionRepository.save(node);
    WorkflowVersion savedVersion = workflowVersionRepository.saveAndFlush(version);
    return new WorkflowGraphDtos.NodeMutation(
        WorkflowGraphDtos.NodeView.from(savedNode),
        WorkflowGraphDtos.DraftState.from(savedVersion));
  }

  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  @TransactionalCommand
  public WorkflowGraphDtos.DraftState deleteNode(
      UUID nodeId, ExpectedVersion expectedVersion, long expectedRevision) {
    NodeDefinition node = requireNode(nodeId);
    WorkflowVersion version =
        beginGraphMutation(node.getWorkflowVersionId(), expectedVersion, expectedRevision);
    if (edgeDefinitionRepository.existsBySourceNodeIdOrTargetNodeId(nodeId, nodeId)) {
      throw new CommandConflictException(
          "WORKFLOW_NODE_HAS_EDGES", "Delete connected edges before deleting this node");
    }
    nodeDefinitionRepository.delete(node);
    WorkflowVersion savedVersion = workflowVersionRepository.saveAndFlush(version);
    return WorkflowGraphDtos.DraftState.from(savedVersion);
  }

  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  @TransactionalCommand
  public WorkflowGraphDtos.EdgeMutation createEdge(
      WorkflowGraphDtos.CreateEdge request,
      ExpectedVersion expectedVersion,
      long expectedRevision) {
    WorkflowVersion version =
        beginGraphMutation(request.workflowVersionId(), expectedVersion, expectedRevision);
    NodeDefinition source =
        requireNodeInVersion(request.sourceNodeId(), request.workflowVersionId(), "sourceNodeId");
    requireNodeInVersion(request.targetNodeId(), request.workflowVersionId(), "targetNodeId");
    validateSourcePort(source, request.sourcePort());

    EdgeDefinition edge =
        EdgeDefinition.create(
            uuidGenerator.generate(),
            request.workflowVersionId(),
            request.sourceNodeId(),
            request.sourcePort(),
            request.targetNodeId(),
            request.conditionJson(),
            request.priority(),
            request.defaultTransition(),
            request.transitionType(),
            request.label(),
            request.configJson());
    EdgeDefinition savedEdge = edgeDefinitionRepository.save(edge);
    WorkflowVersion savedVersion = workflowVersionRepository.saveAndFlush(version);
    return new WorkflowGraphDtos.EdgeMutation(
        WorkflowGraphDtos.EdgeView.from(savedEdge),
        WorkflowGraphDtos.DraftState.from(savedVersion));
  }

  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  @TransactionalCommand
  public WorkflowGraphDtos.EdgeMutation updateEdge(
      UUID edgeId,
      WorkflowGraphDtos.UpdateEdge request,
      ExpectedVersion expectedVersion,
      long expectedRevision) {
    EdgeDefinition edge = requireEdge(edgeId);
    WorkflowVersion version =
        beginGraphMutation(edge.getWorkflowVersionId(), expectedVersion, expectedRevision);
    NodeDefinition source =
        requireNodeInVersion(request.sourceNodeId(), edge.getWorkflowVersionId(), "sourceNodeId");
    requireNodeInVersion(request.targetNodeId(), edge.getWorkflowVersionId(), "targetNodeId");
    validateSourcePort(source, request.sourcePort());
    edge.update(
        request.sourceNodeId(),
        request.sourcePort(),
        request.targetNodeId(),
        request.conditionJson(),
        request.priority(),
        request.defaultTransition(),
        request.transitionType(),
        request.label(),
        request.configJson());
    EdgeDefinition savedEdge = edgeDefinitionRepository.save(edge);
    WorkflowVersion savedVersion = workflowVersionRepository.saveAndFlush(version);
    return new WorkflowGraphDtos.EdgeMutation(
        WorkflowGraphDtos.EdgeView.from(savedEdge),
        WorkflowGraphDtos.DraftState.from(savedVersion));
  }

  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  @TransactionalCommand
  public WorkflowGraphDtos.DraftState deleteEdge(
      UUID edgeId, ExpectedVersion expectedVersion, long expectedRevision) {
    EdgeDefinition edge = requireEdge(edgeId);
    WorkflowVersion version =
        beginGraphMutation(edge.getWorkflowVersionId(), expectedVersion, expectedRevision);
    edgeDefinitionRepository.delete(edge);
    WorkflowVersion savedVersion = workflowVersionRepository.saveAndFlush(version);
    return WorkflowGraphDtos.DraftState.from(savedVersion);
  }

  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  @TransactionalCommand
  public WorkflowGraphDtos.GraphView replaceGraph(
      UUID workflowVersionId,
      WorkflowGraphDtos.ReplaceGraph request,
      ExpectedVersion expectedVersion,
      long expectedRevision) {
    WorkflowVersion version =
        beginGraphMutation(workflowVersionId, expectedVersion, expectedRevision);
    Map<String, WorkflowGraphDtos.GraphNode> requestedNodes = new LinkedHashMap<>();
    Set<String> nodeKeys = new HashSet<>();
    for (WorkflowGraphDtos.GraphNode node : request.nodes()) {
      if (node == null) {
        throw new UnprocessableCommandException(
            "WORKFLOW_GRAPH_NODE_INVALID", "Graph nodes must not be null");
      }
      if (node.clientRef() == null || node.clientRef().isBlank()) {
        throw new UnprocessableCommandException(
            "WORKFLOW_GRAPH_CLIENT_REF_REQUIRED", "Every graph node requires a clientRef");
      }
      validateGraphNode(node);
      if (requestedNodes.putIfAbsent(node.clientRef(), node) != null) {
        throw new UnprocessableCommandException(
            "WORKFLOW_GRAPH_CLIENT_REF_DUPLICATE", "Graph node clientRef must be unique");
      }
      if (!nodeKeys.add(node.nodeKey())) {
        throw new CommandConflictException(
            "WORKFLOW_NODE_KEY_CONFLICT", "Node key already exists in this WorkflowVersion");
      }
      validateNodeManifest(
          node.nodeKey(), node.nodeType(), node.configSchemaVersion(), node.configJson());
    }

    Set<String> edgeRefs = new HashSet<>();
    for (WorkflowGraphDtos.GraphEdge edge : request.edges()) {
      if (edge == null) {
        throw new UnprocessableCommandException(
            "WORKFLOW_GRAPH_EDGE_INVALID", "Graph edges must not be null");
      }
      if (edge.clientRef() == null
          || edge.clientRef().isBlank()
          || !edgeRefs.add(edge.clientRef())) {
        throw new UnprocessableCommandException(
            "WORKFLOW_EDGE_CLIENT_REF_INVALID", "Graph edge clientRef must be present and unique");
      }
      if (edge.sourceClientRef() == null
          || edge.sourceClientRef().isBlank()
          || edge.targetClientRef() == null
          || edge.targetClientRef().isBlank()) {
        throw new UnprocessableCommandException(
            "WORKFLOW_EDGE_NODE_REF_REQUIRED",
            "Every edge requires a sourceClientRef and targetClientRef");
      }
      WorkflowGraphDtos.GraphNode source = requestedNodes.get(edge.sourceClientRef());
      if (source == null || !requestedNodes.containsKey(edge.targetClientRef())) {
        throw new UnprocessableCommandException(
            "WORKFLOW_EDGE_NODE_MISSING", "Every edge endpoint must reference a submitted node");
      }
      validateGraphEdge(edge);
      NodeTypeManifest sourceManifest = manifest(source.nodeType());
      if (!sourceManifest.outputPorts().contains(edge.sourcePort())) {
        throw new UnprocessableCommandException(
            "NODE_OUTPUT_PORT_UNKNOWN",
            "Edge sourcePort is not declared by the source node manifest");
      }
    }

    Map<String, NodeDefinition> nodesByClientRef = new LinkedHashMap<>();
    for (WorkflowGraphDtos.GraphNode requested : request.nodes()) {
      try {
        nodesByClientRef.put(
            requested.clientRef(),
            NodeDefinition.create(
                uuidGenerator.generate(),
                workflowVersionId,
                requested.nodeKey(),
                requested.nodeType(),
                requested.name(),
                requested.description(),
                requested.configSchemaVersion(),
                requested.configJson(),
                requested.inputSchemaJson(),
                requested.outputSchemaJson(),
                requested.positionJson()));
      } catch (IllegalArgumentException | NullPointerException exception) {
        throw new UnprocessableCommandException(
            "WORKFLOW_GRAPH_NODE_INVALID", "Graph node contains an invalid value");
      }
    }

    List<EdgeDefinition> requestedEdges = new ArrayList<>();
    for (WorkflowGraphDtos.GraphEdge requested : request.edges()) {
      try {
        requestedEdges.add(
            EdgeDefinition.create(
                uuidGenerator.generate(),
                workflowVersionId,
                nodesByClientRef.get(requested.sourceClientRef()).getId(),
                requested.sourcePort(),
                nodesByClientRef.get(requested.targetClientRef()).getId(),
                requested.conditionJson(),
                requested.priority(),
                requested.defaultTransition(),
                requested.transitionType(),
                requested.label(),
                requested.configJson()));
      } catch (IllegalArgumentException | NullPointerException exception) {
        throw new UnprocessableCommandException(
            "WORKFLOW_GRAPH_EDGE_INVALID", "Graph edge contains an invalid value");
      }
    }

    edgeDefinitionRepository.deleteAllByWorkflowVersionId(workflowVersionId);
    edgeDefinitionRepository.flush();
    nodeDefinitionRepository.deleteAllByWorkflowVersionId(workflowVersionId);
    nodeDefinitionRepository.flush();

    List<NodeDefinition> savedNodes =
        nodeDefinitionRepository.saveAllAndFlush(nodesByClientRef.values());

    List<EdgeDefinition> savedEdges = edgeDefinitionRepository.saveAllAndFlush(requestedEdges);
    WorkflowVersion savedVersion = workflowVersionRepository.saveAndFlush(version);
    return new WorkflowGraphDtos.GraphView(
        savedNodes.stream().map(WorkflowGraphDtos.NodeView::from).toList(),
        savedEdges.stream().map(WorkflowGraphDtos.EdgeView::from).toList(),
        WorkflowGraphDtos.DraftState.from(savedVersion));
  }

  @TransactionalQuery
  public WorkflowGraphDtos.NodeView getNode(UUID nodeId) {
    return WorkflowGraphDtos.NodeView.from(requireNode(nodeId));
  }

  @TransactionalQuery
  public WorkflowGraphDtos.EdgeView getEdge(UUID edgeId) {
    return WorkflowGraphDtos.EdgeView.from(requireEdge(edgeId));
  }

  @TransactionalQuery
  public List<WorkflowGraphDtos.NodeView> listNodes(UUID workflowVersionId) {
    requireVersion(workflowVersionId);
    return nodeDefinitionRepository
        .findAllByWorkflowVersionIdOrderByNodeKeyAsc(workflowVersionId)
        .stream()
        .map(WorkflowGraphDtos.NodeView::from)
        .toList();
  }

  @TransactionalQuery
  public List<WorkflowGraphDtos.EdgeView> listEdges(UUID workflowVersionId) {
    requireVersion(workflowVersionId);
    return edgeDefinitionRepository
        .findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(workflowVersionId)
        .stream()
        .map(WorkflowGraphDtos.EdgeView::from)
        .toList();
  }

  private WorkflowVersion beginGraphMutation(
      UUID workflowVersionId, ExpectedVersion expectedVersion, long expectedRevision) {
    WorkflowVersion version = requireVersion(workflowVersionId);
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(version.getLockVersion()), expectedVersion);
    try {
      version.recordGraphMutation(expectedRevision);
    } catch (StaleDraftRevisionException exception) {
      throw new CommandConflictException(
          "WORKFLOW_DRAFT_REVISION_CONFLICT", exception.getMessage());
    } catch (IllegalStateException exception) {
      throw new CommandConflictException("WORKFLOW_VERSION_NOT_EDITABLE", exception.getMessage());
    }
    return version;
  }

  private WorkflowVersion requireVersion(UUID workflowVersionId) {
    return workflowVersionRepository
        .findById(workflowVersionId)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_VERSION_NOT_FOUND", "Workflow version was not found"));
  }

  private NodeDefinition requireNode(UUID nodeId) {
    return nodeDefinitionRepository
        .findById(nodeId)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_NODE_NOT_FOUND", "Workflow node was not found"));
  }

  private EdgeDefinition requireEdge(UUID edgeId) {
    return edgeDefinitionRepository
        .findById(edgeId)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_EDGE_NOT_FOUND", "Workflow edge was not found"));
  }

  private NodeDefinition requireNodeInVersion(UUID nodeId, UUID workflowVersionId, String field) {
    NodeDefinition node = requireNode(nodeId);
    if (!node.getWorkflowVersionId().equals(workflowVersionId)) {
      throw new CommandConflictException(
          "WORKFLOW_GRAPH_VERSION_MISMATCH",
          field + " must reference a node in the edge WorkflowVersion");
    }
    return node;
  }

  private void validateGraphNode(WorkflowGraphDtos.GraphNode node) {
    if (node.nodeKey() == null || node.nodeKey().isBlank()) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_NODE_KEY_REQUIRED", "Every graph node requires a nodeKey");
    }
    if (!GRAPH_KEY_PATTERN.matcher(node.nodeKey()).matches()) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_NODE_KEY_INVALID",
          "Graph node nodeKey must start with a letter and contain only letters, numbers, '.', '_' or '-'");
    }
    if (node.name() == null || node.name().isBlank()) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_NODE_NAME_REQUIRED", "Every graph node requires a name");
    }
    if (node.configSchemaVersion() < 1) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_CONFIG_SCHEMA_VERSION_INVALID",
          "Graph node configSchemaVersion must be positive");
    }
    requireGraphObject(node.configJson(), "configJson");
    requireNullableGraphObject(node.inputSchemaJson(), "inputSchemaJson");
    requireNullableGraphObject(node.outputSchemaJson(), "outputSchemaJson");
    requireGraphObject(node.positionJson(), "positionJson");
  }

  private void validateGraphEdge(WorkflowGraphDtos.GraphEdge edge) {
    if (edge.sourcePort() == null || edge.sourcePort().isBlank()) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_SOURCE_PORT_REQUIRED", "Every graph edge requires a sourcePort");
    }
    if (!GRAPH_KEY_PATTERN.matcher(edge.sourcePort()).matches()) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_SOURCE_PORT_INVALID", "Graph edge sourcePort is invalid");
    }
    if (edge.priority() < 0) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_PRIORITY_INVALID", "Graph edge priority must not be negative");
    }
    if (edge.transitionType() == null) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_TRANSITION_TYPE_REQUIRED",
          "Every graph edge requires a transitionType");
    }
    requireNullableGraphObject(edge.conditionJson(), "conditionJson");
    requireGraphObject(edge.configJson(), "configJson");
  }

  private void requireGraphObject(JsonNode value, String field) {
    if (value == null || !value.isObject()) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_JSON_OBJECT_REQUIRED", field + " must be a JSON object");
    }
  }

  private void requireNullableGraphObject(JsonNode value, String field) {
    if (value != null && !value.isObject()) {
      throw new UnprocessableCommandException(
          "WORKFLOW_GRAPH_JSON_OBJECT_INVALID", field + " must be a JSON object when provided");
    }
  }

  private void validateNodeManifest(
      String nodeKey, String nodeTypeValue, int configSchemaVersion, JsonNode config) {
    NodeTypeManifest manifest = manifest(nodeTypeValue);
    var blocking =
        manifest.validate(nodeKey, configSchemaVersion, config).stream()
            .filter(issue -> issue.blocking())
            .toList();
    if (!blocking.isEmpty()) {
      String summary =
          blocking.stream()
              .limit(5)
              .map(issue -> issue.code() + "@" + issue.fieldPath())
              .collect(java.util.stream.Collectors.joining(", "));
      throw new UnprocessableCommandException(
          "NODE_CONFIG_VALIDATION_FAILED", "Node config violates its manifest: " + summary);
    }
  }

  private void validateSourcePort(NodeDefinition source, String sourcePort) {
    if (!manifest(source.getNodeType()).outputPorts().contains(sourcePort)) {
      throw new UnprocessableCommandException(
          "NODE_OUTPUT_PORT_UNKNOWN",
          "Edge sourcePort is not declared by the source node manifest");
    }
  }

  private NodeTypeManifest manifest(String nodeTypeValue) {
    try {
      NodeType nodeType = NodeType.valueOf(nodeTypeValue.trim().toUpperCase(Locale.ROOT));
      return nodeTypeRegistry.require(nodeType);
    } catch (RuntimeException exception) {
      throw new UnprocessableCommandException("NODE_TYPE_UNKNOWN", "Node type is not registered");
    }
  }
}
