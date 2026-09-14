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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class WorkflowGraphService {

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
      if (node.clientRef() == null || node.clientRef().isBlank()) {
        throw new UnprocessableCommandException(
            "WORKFLOW_GRAPH_CLIENT_REF_REQUIRED", "Every graph node requires a clientRef");
      }
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
      if (edge.clientRef() == null
          || edge.clientRef().isBlank()
          || !edgeRefs.add(edge.clientRef())) {
        throw new UnprocessableCommandException(
            "WORKFLOW_EDGE_CLIENT_REF_INVALID", "Graph edge clientRef must be present and unique");
      }
      WorkflowGraphDtos.GraphNode source = requestedNodes.get(edge.sourceClientRef());
      if (source == null || !requestedNodes.containsKey(edge.targetClientRef())) {
        throw new UnprocessableCommandException(
            "WORKFLOW_EDGE_NODE_MISSING", "Every edge endpoint must reference a submitted node");
      }
      NodeTypeManifest sourceManifest = manifest(source.nodeType());
      if (!sourceManifest.outputPorts().contains(edge.sourcePort())) {
        throw new UnprocessableCommandException(
            "NODE_OUTPUT_PORT_UNKNOWN",
            "Edge sourcePort is not declared by the source node manifest");
      }
    }

    edgeDefinitionRepository.deleteAllByWorkflowVersionId(workflowVersionId);
    edgeDefinitionRepository.flush();
    nodeDefinitionRepository.deleteAllByWorkflowVersionId(workflowVersionId);
    nodeDefinitionRepository.flush();

    Map<String, NodeDefinition> persistedByClientRef = new LinkedHashMap<>();
    for (WorkflowGraphDtos.GraphNode requested : request.nodes()) {
      NodeDefinition node =
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
              requested.positionJson());
      persistedByClientRef.put(requested.clientRef(), node);
    }
    List<NodeDefinition> savedNodes =
        nodeDefinitionRepository.saveAllAndFlush(persistedByClientRef.values());

    List<EdgeDefinition> savedEdges =
        edgeDefinitionRepository.saveAllAndFlush(
            request.edges().stream()
                .map(
                    edge ->
                        EdgeDefinition.create(
                            uuidGenerator.generate(),
                            workflowVersionId,
                            persistedByClientRef.get(edge.sourceClientRef()).getId(),
                            edge.sourcePort(),
                            persistedByClientRef.get(edge.targetClientRef()).getId(),
                            edge.conditionJson(),
                            edge.priority(),
                            edge.defaultTransition(),
                            edge.transitionType(),
                            edge.label(),
                            edge.configJson()))
                .toList());
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
