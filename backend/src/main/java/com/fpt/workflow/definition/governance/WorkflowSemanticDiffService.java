package com.fpt.workflow.definition.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.definition.validation.ValidationDefinition;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Stable-key semantic diff. Database identifiers are deliberately excluded from comparisons. */
@Service
public class WorkflowSemanticDiffService {

  private final WorkflowValidationService validationService;
  private final ObjectMapper objectMapper;

  public WorkflowSemanticDiffService(
      WorkflowValidationService validationService, ObjectMapper objectMapper) {
    this.validationService = validationService;
    this.objectMapper = objectMapper;
  }

  @TransactionalQuery
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'OPERATOR', 'ADMIN')")
  public SemanticDiff diff(UUID fromVersionId, UUID toVersionId) {
    ValidationDefinition from = validationService.loadCurrent(fromVersionId);
    ValidationDefinition to = validationService.loadCurrent(toVersionId);
    if (!from.version().getDefinitionId().equals(to.version().getDefinitionId())) {
      throw new CommandConflictException(
          "WORKFLOW_DIFF_DEFINITION_MISMATCH",
          "Semantic diff requires versions of the same WorkflowDefinition");
    }
    return compare(from, to);
  }

  SemanticDiff compare(ValidationDefinition from, ValidationDefinition to) {
    Map<UUID, String> fromNodeKeys = nodeKeys(from.nodes());
    Map<UUID, String> toNodeKeys = nodeKeys(to.nodes());
    return new SemanticDiff(
        from.version().getId(),
        to.version().getId(),
        changes(nodes(from.nodes()), nodes(to.nodes())),
        changes(edges(from.edges(), fromNodeKeys), edges(to.edges(), toNodeKeys)),
        changes(forms(from.forms()), forms(to.forms())),
        changes(participantPolicies(from.nodes()), participantPolicies(to.nodes())),
        changes(slaPolicies(from.nodes()), slaPolicies(to.nodes())),
        changes(connectorActionVersions(from.nodes()), connectorActionVersions(to.nodes())),
        changes(variables(from.variables()), variables(to.variables())));
  }

  private Map<UUID, String> nodeKeys(List<NodeDefinition> nodes) {
    return nodes.stream()
        .collect(Collectors.toMap(NodeDefinition::getId, NodeDefinition::getNodeKey));
  }

  private Map<String, JsonNode> nodes(List<NodeDefinition> nodes) {
    return nodes.stream()
        .collect(
            Collectors.toMap(
                NodeDefinition::getNodeKey,
                node -> {
                  ObjectNode value = objectMapper.createObjectNode();
                  value.put("type", node.getNodeType());
                  value.put("name", node.getName());
                  value.put("description", node.getDescription());
                  value.put("configSchemaVersion", node.getConfigSchemaVersion());
                  value.set("config", node.getConfigJson());
                  value.set("inputSchema", node.getInputSchemaJson());
                  value.set("outputSchema", node.getOutputSchemaJson());
                  return value;
                },
                (left, right) -> right,
                LinkedHashMap::new));
  }

  private Map<String, JsonNode> edges(List<EdgeDefinition> edges, Map<UUID, String> nodeKeys) {
    return edges.stream()
        .sorted(Comparator.comparing(EdgeDefinition::getId))
        .collect(
            Collectors.toMap(
                edge ->
                    requiredNodeKey(nodeKeys, edge.getSourceNodeId())
                        + ":"
                        + edge.getSourcePort()
                        + "->"
                        + requiredNodeKey(nodeKeys, edge.getTargetNodeId())
                        + "#"
                        + edge.getPriority(),
                edge -> {
                  ObjectNode value = objectMapper.createObjectNode();
                  value.set("condition", edge.getConditionJson());
                  value.put("default", edge.isDefaultTransition());
                  value.put("transitionType", edge.getTransitionType().name());
                  value.put("label", edge.getLabel());
                  value.set("config", edge.getConfigJson());
                  return value;
                },
                (left, right) -> right,
                LinkedHashMap::new));
  }

  private String requiredNodeKey(Map<UUID, String> nodeKeys, UUID id) {
    return Objects.requireNonNull(nodeKeys.get(id), "Edge references an unknown node");
  }

  private Map<String, JsonNode> forms(List<WorkflowForm> forms) {
    return forms.stream()
        .collect(
            Collectors.toMap(
                WorkflowForm::getFormKey,
                form -> {
                  ObjectNode value = objectMapper.createObjectNode();
                  value.put("type", form.getFormType().name());
                  value.set("schema", form.getSchemaJson());
                  return value;
                },
                (left, right) -> right,
                LinkedHashMap::new));
  }

  private Map<String, JsonNode> participantPolicies(List<NodeDefinition> nodes) {
    return selectedNodeConfig(nodes, Set.of("participant", "participants", "participantResolver"));
  }

  private Map<String, JsonNode> slaPolicies(List<NodeDefinition> nodes) {
    return selectedNodeConfig(nodes, Set.of("sla", "slaPolicy", "dueAtPolicy"));
  }

  private Map<String, JsonNode> connectorActionVersions(List<NodeDefinition> nodes) {
    return selectedNodeConfig(
        nodes, Set.of("connectorActionVersionId", "connectorKey", "actionKey", "actionVersion"));
  }

  private Map<String, JsonNode> selectedNodeConfig(
      List<NodeDefinition> nodes, Set<String> selectedFields) {
    Map<String, JsonNode> result = new LinkedHashMap<>();
    nodes.stream()
        .sorted(Comparator.comparing(NodeDefinition::getNodeKey))
        .forEach(
            node -> {
              ObjectNode selected = objectMapper.createObjectNode();
              selectedFields.stream()
                  .sorted()
                  .filter(node.getConfigJson()::has)
                  .forEach(field -> selected.set(field, node.getConfigJson().get(field)));
              if (!selected.isEmpty()) {
                result.put(node.getNodeKey(), selected);
              }
            });
    return result;
  }

  private Map<String, JsonNode> variables(List<WorkflowVariable> variables) {
    return variables.stream()
        .collect(
            Collectors.toMap(
                WorkflowVariable::getKey,
                variable -> {
                  ObjectNode value = objectMapper.createObjectNode();
                  value.set("type", objectMapper.valueToTree(variable.getType()));
                  value.put("scope", variable.getScope().name());
                  value.set("default", variable.getDefaultJson());
                  value.put("mutable", variable.isMutable());
                  value.put("sensitive", variable.isSensitive());
                  return value;
                },
                (left, right) -> right,
                LinkedHashMap::new));
  }

  private List<SemanticChange> changes(Map<String, JsonNode> before, Map<String, JsonNode> after) {
    Set<String> keys = new TreeSet<>();
    keys.addAll(before.keySet());
    keys.addAll(after.keySet());
    List<SemanticChange> result = new ArrayList<>();
    for (String key : keys) {
      JsonNode oldValue = before.get(key);
      JsonNode newValue = after.get(key);
      if (Objects.equals(oldValue, newValue)) {
        continue;
      }
      ChangeType type =
          oldValue == null
              ? ChangeType.ADDED
              : newValue == null ? ChangeType.REMOVED : ChangeType.MODIFIED;
      result.add(
          new SemanticChange(
              key,
              type,
              oldValue == null ? null : oldValue.deepCopy(),
              newValue == null ? null : newValue.deepCopy()));
    }
    return List.copyOf(result);
  }

  public enum ChangeType {
    ADDED,
    REMOVED,
    MODIFIED
  }

  public record SemanticChange(
      String resourceKey, ChangeType changeType, JsonNode before, JsonNode after) {}

  public record SemanticDiff(
      UUID fromVersionId,
      UUID toVersionId,
      List<SemanticChange> nodes,
      List<SemanticChange> edges,
      List<SemanticChange> forms,
      List<SemanticChange> participantPolicies,
      List<SemanticChange> slaPolicies,
      List<SemanticChange> connectorActionVersions,
      List<SemanticChange> variables) {
    public boolean hasChanges() {
      return !nodes.isEmpty()
          || !edges.isEmpty()
          || !forms.isEmpty()
          || !participantPolicies.isEmpty()
          || !slaPolicies.isEmpty()
          || !connectorActionVersions.isEmpty()
          || !variables.isEmpty();
    }
  }
}
