package com.fpt.workflow.definition.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.validation.ValidationCompilation;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.resolver.participant.ParticipantResolverRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dry-run simulation for a workflow definition version (P2-08 §22.8 / §23.3). Reads only draft
 * definition rows; never creates Event/NodeExecution/TaskExecution or calls external connectors.
 *
 * <p>Result exposes: validation gate, sampled participant resolution, selected routes, per-node
 * task fan-out, sub-workflow input mapping, potential failures/warnings, and a deterministic
 * traversal order. Consumers must treat this output as simulated (never production side effects).
 */
@Service
public class WorkflowSimulationService {

  private final WorkflowValidationService validationService;
  private final EdgeDefinitionRepository edgeRepository;
  private final NodeDefinitionRepository nodeRepository;
  private final ParticipantResolverRegistry participantRegistry;

  public WorkflowSimulationService(
      WorkflowValidationService validationService,
      EdgeDefinitionRepository edgeRepository,
      NodeDefinitionRepository nodeDefinitionRepository,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          ParticipantResolverRegistry participantRegistry) {
    this.validationService = validationService;
    this.edgeRepository = edgeRepository;
    this.nodeRepository = nodeDefinitionRepository;
    this.participantRegistry = participantRegistry;
  }

  /**
   * Simulates a workflow version using the provided sample context (ticket.data plus subject list).
   * Sample data must be server-validated before it influences participant resolution (§9.5).
   */
  @Transactional(readOnly = true)
  public SimulationResult simulate(UUID workflowVersionId, SampleContext sample) {
    ValidationCompilation validation = validationService.compileCurrent(workflowVersionId);
    List<SimulatedTransition> transitions = new ArrayList<>();
    List<SimulatedParticipant> participants = new ArrayList<>();
    List<SimulatedMultiInstancePlan> multiInstancePlans = new ArrayList<>();
    List<SimulatedSubWorkflow> subWorkflows = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    List<NodeDefinition> nodes = nodeRepository.findAllByWorkflowVersionIdOrderByNodeKeyAsc(workflowVersionId);
    Map<UUID, NodeDefinition> nodesById =
        nodes.stream().collect(Collectors.toMap(NodeDefinition::getId, n -> n));
    List<com.fpt.workflow.definition.domain.EdgeDefinition> edges =
        edgeRepository.findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(workflowVersionId);

    // Build outgoing/incoming maps by node ID and port for traversal
    Map<UUID, List<com.fpt.workflow.definition.domain.EdgeDefinition>> outgoing = new HashMap<>();
    Map<UUID, List<com.fpt.workflow.definition.domain.EdgeDefinition>> incoming = new HashMap<>();
    for (var e : edges) {
      outgoing.computeIfAbsent(e.getSourceNodeId(), k -> new ArrayList<>()).add(e);
      incoming.computeIfAbsent(e.getTargetNodeId(), k -> new ArrayList<>()).add(e);
    }

    if (!validation.valid()) {
      warnings.add("Definition failed static validation: publish is blocked; simulation ran in advisory mode");
    }
    warnings.addAll(
        validation.issues().stream()
            .filter(i -> "WARNING".equalsIgnoreCase(i.severity().name()) || "ACK_REQUIRED_WARNING".equalsIgnoreCase(i.severity().name()))
            .map(i -> i.code() + ": " + i.message())
            .toList());

    // Deterministic traversal from every START node: follow declared output ports in priority order
    List<NodeDefinition> starts = nodes.stream().filter(n -> "START".equals(n.getNodeType())).toList();
    Set<UUID> visited = new HashSet<>();
    List<UUID> fringe = starts.stream().map(NodeDefinition::getId).collect(Collectors.toCollection(ArrayList::new));
    while (!fringe.isEmpty()) {
      UUID nodeId = fringe.remove(0);
      if (!visited.add(nodeId)) {
        continue;
      }
      NodeDefinition node = nodesById.get(nodeId);
      if (node == null) {
        continue;
      }
      // Multi-instance planning (P1-08): deterministic collection binding for simulation sample
      JsonNode miConfig = node.getConfigJson() != null ? node.getConfigJson().path("multiInstance") : null;
      if (miConfig != null && miConfig.isObject()) {
        String collectionPath = miConfig.path("collection").asText(miConfig.path("collectionPath").asText(null));
        String itemVariable = miConfig.path("itemVariable").asText("item");
        int collectionSize = 1;
        if (collectionPath != null && !collectionPath.isBlank() && sample.ticketData() != null) {
          if (sample.ticketData().isObject()) {
            // Best-effort: derive fan-out from sample ticket.data.<leaf> where <leaf> matches the
            // configured collection reference.
            String leaf = collectionPath.contains(".") ? collectionPath.substring(collectionPath.lastIndexOf('.') + 1) : collectionPath;
            leaf = leaf.replace("${", "").replace("}", "").replace("ticket.data.", "");
            JsonNode leafNode = sample.ticketData().path(leaf);
            if (leafNode.isArray()) {
              collectionSize = leafNode.size();
            }
          }
        }
        multiInstancePlans.add(
            new SimulatedMultiInstancePlan(
                node.getNodeKey(), collectionPath, itemVariable, collectionSize));
        warnings.add(
            "Multi-instance node '" + node.getNodeKey() + "' would fan out " + collectionSize
                + " occurrence(s) on this sample; each occurrence binds itemVariable '" + itemVariable + "'");
      }

      // Participant resolution (sampled): participant config → sampled candidate set or vacancy/failure
      JsonNode participantCfg = null;
      if (node.getConfigJson() != null) {
        if (node.getConfigJson().hasNonNull("participant")) {
          participantCfg = node.getConfigJson().get("participant");
        } else if (node.getConfigJson().hasNonNull("assignee")) {
          participantCfg = node.getConfigJson().get("assignee");
        }
      }
      if (participantCfg != null && participantCfg.isObject() && participantRegistry != null) {
        String type = participantCfg.path("type").asText(participantCfg.path("sourceType").asText(null));
        if (type == null || type.isBlank()) {
          type = participantCfg.path("resolver").path("type").asText(null);
          if (type == null) {
            type = participantCfg.path("resolver").path("sourceType").asText(null);
          }
        }
        String status = participantRegistry.hasResolver(type) ? "RESOLVED" : "UNKNOWN_PARTICIPANT_TYPE";
        String details = type != null ? type : "(missing)";
        participants.add(new SimulatedParticipant(node.getNodeKey(), details, status));
        if ("UNKNOWN_PARTICIPANT_TYPE".equals(status)) {
          warnings.add("Participant resolver for '" + node.getNodeKey() + "': unknown type " + details);
        }
      }

      // Sub-workflow mapping (deterministic child key; stub/no-side-effect)
      if (node.getConfigJson() != null
          && node.getConfigJson().hasNonNull("childWorkflowDefinitionKey")) {
        String childKey = node.getConfigJson().path("childWorkflowDefinitionKey").asText(null);
        if (childKey == null) {
          childKey = node.getConfigJson().path("childKey").asText("UNKNOWN");
        }
        subWorkflows.add(
            new SimulatedSubWorkflow(node.getNodeKey(), childKey, childKey, "STUB_DRY_RUN", "WOULD_CREATE_CHILD_EVENT"));
      }

      // Selected routes: report declared outputPorts and whether a matching edge exists (priority order)
      List<com.fpt.workflow.definition.domain.EdgeDefinition> out = outgoing.getOrDefault(nodeId, List.of());
      Set<String> handledPorts = out.stream().map(e -> e.getSourcePort()).collect(Collectors.toSet());
      if (!out.isEmpty()) {
        for (var e : out) {
          transitions.add(
              new SimulatedTransition(node.getNodeKey(), e.getSourcePort(), nodesById.get(e.getTargetNodeId()) != null
                  ? nodesById.get(e.getTargetNodeId()).getNodeKey()
                  : e.getTargetNodeId().toString(), e.getPriority(), true, e.getId()));
        }
      }
      // Handle unhandled output ports (join fallthrough, etc.): surfaced as warnings rather than routed
      // (compiler already gates publish for missing required routes; simulation never invents an edge).
      if (node.getNodeType() != null && !"END".equals(node.getNodeType()) && handledPorts.isEmpty() && out.isEmpty()) {
        // Non-terminal without outgoing is expected to be caught by graph validation; surface warning only.
        warnings.add("Node '" + node.getNodeKey() + "' (" + node.getNodeType() + ") has no outgoing on this definition");
      }

      // Enqueue targets deterministically by priority (lower first, stable by id)
      out.stream()
          .sorted(java.util.Comparator.comparingInt(com.fpt.workflow.definition.domain.EdgeDefinition::getPriority)
              .thenComparing(e -> e.getId().toString()))
          .forEach(e -> fringe.add(e.getTargetNodeId()));
    }

    return new SimulationResult(
        workflowVersionId,
        validation.workflowVersionId(),
        validation.revision(),
        validation.definitionChecksum(),
        validation.valid(),
        validation.publishable(),
        validation.issues().stream().map(i -> i.code() + ": " + i.message()).toList(),
        transitions,
        participants,
        multiInstancePlans,
        subWorkflows,
        List.copyOf(warnings));
  }

  public record SampleContext(JsonNode ticketData, JsonNode subjects) {}

  public record SimulatedTransition(
      String sourceNodeKey, String outputPort, String targetNodeKey, int priority, boolean selected, UUID edgeId) {}

  public record SimulatedParticipant(String nodeKey, String resolverType, String status) {}

  public record SimulatedMultiInstancePlan(
      String nodeKey, String collectionPath, String itemVariable, int plannedCount) {}

  public record SimulatedSubWorkflow(
      String nodeKey,
      String childDefinitionKey,
      String childKey,
      String resolution,
      String disposition) {}

  public record SimulationResult(
      UUID workflowVersionId,
      UUID validatedVersionId,
      long revision,
      String definitionChecksum,
      boolean valid,
      boolean publishable,
      List<String> validationIssues,
      List<SimulatedTransition> transitions,
      List<SimulatedParticipant> participants,
      List<SimulatedMultiInstancePlan> multiInstancePlans,
      List<SimulatedSubWorkflow> subWorkflows,
      List<String> warnings) {
  }
}
