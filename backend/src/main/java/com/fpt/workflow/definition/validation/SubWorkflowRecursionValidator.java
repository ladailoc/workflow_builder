package com.fpt.workflow.definition.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.ValidationSeverity;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SubWorkflowRecursionValidator {

  private final WorkflowDefinitionRepository definitionRepository;
  private final WorkflowVersionRepository versionRepository;
  private final NodeDefinitionRepository nodeRepository;

  public SubWorkflowRecursionValidator(
      WorkflowDefinitionRepository definitionRepository,
      WorkflowVersionRepository versionRepository,
      NodeDefinitionRepository nodeRepository) {
    this.definitionRepository =
        Objects.requireNonNull(definitionRepository, "definitionRepository");
    this.versionRepository = Objects.requireNonNull(versionRepository, "versionRepository");
    this.nodeRepository = Objects.requireNonNull(nodeRepository, "nodeRepository");
  }

  public void validate(ValidationDefinition definition, List<CompilerIssue> issues) {
    WorkflowVersion version = definition.version();
    WorkflowDefinition currentDef =
        definitionRepository.findById(version.getDefinitionId()).orElse(null);
    if (currentDef == null) {
      return;
    }

    String currentKey = currentDef.getKey();
    UUID currentId = currentDef.getId();

    for (NodeDefinition node : definition.nodes()) {
      if (!"SUB_WORKFLOW".equalsIgnoreCase(node.getNodeType())) {
        continue;
      }

      JsonNode config = node.getConfigJson();
      String childKey = config.path("childWorkflowDefinitionKey").asText(null);
      if (childKey == null || childKey.isBlank()) {
        childKey = config.path("childWorkflowKey").asText(null);
      }
      String childIdStr = config.path("childWorkflowDefinitionId").asText(null);

      // Direct recursion check
      boolean isDirect = false;
      if (childKey != null && childKey.equalsIgnoreCase(currentKey)) {
        isDirect = true;
      } else if (childIdStr != null && !childIdStr.isBlank()) {
        try {
          if (UUID.fromString(childIdStr).equals(currentId)) {
            isDirect = true;
          }
        } catch (IllegalArgumentException ignored) {
        }
      }

      if (isDirect) {
        issues.add(
            new CompilerIssue(
                "SUBWORKFLOW_RECURSION_DETECTED",
                ValidationSeverity.ERROR,
                "NODE",
                node.getId(),
                "/config/childWorkflowDefinitionKey",
                "Direct recursion is forbidden: SubWorkflow node '"
                    + node.getNodeKey()
                    + "' references its own workflow '"
                    + currentKey
                    + "'",
                "Remove self-referencing subworkflow call",
                null));
        continue;
      }

      // Indirect recursion check via DFS
      if (childKey != null && !childKey.isBlank()) {
        LinkedHashSet<String> path = new LinkedHashSet<>();
        path.add(currentKey);
        dfsCheckRecursion(childKey, currentKey, path, node.getId(), issues, new HashSet<>());
      }
    }
  }

  private void dfsCheckRecursion(
      String targetKey,
      String rootKey,
      LinkedHashSet<String> path,
      UUID sourceNodeId,
      List<CompilerIssue> issues,
      Set<String> visited) {
    if (targetKey.equalsIgnoreCase(rootKey) || path.contains(targetKey)) {
      List<String> cycleList = new ArrayList<>(path);
      cycleList.add(targetKey);
      String cycleStr = String.join(" -> ", cycleList);
      issues.add(
          new CompilerIssue(
              "SUBWORKFLOW_RECURSION_DETECTED",
              ValidationSeverity.ERROR,
              "NODE",
              sourceNodeId,
              "/config/childWorkflowDefinitionKey",
              "Indirect sub-workflow recursion cycle detected: " + cycleStr,
              "Break cyclic dependency between workflows",
              null));
      return;
    }

    if (!visited.add(targetKey)) {
      return;
    }

    Optional<WorkflowDefinition> childDefOpt = definitionRepository.findByKey(targetKey);
    if (childDefOpt.isEmpty()) {
      return;
    }
    WorkflowDefinition childDef = childDefOpt.get();

    UUID versionId = childDef.getCurrentPublishedVersionId();
    if (versionId == null) {
      versionId =
          versionRepository
              .findByDefinitionIdAndStatus(childDef.getId(), WorkflowVersionStatus.DRAFT)
              .map(WorkflowVersion::getId)
              .orElse(null);
    }

    if (versionId == null) {
      return;
    }

    List<NodeDefinition> childNodes =
        nodeRepository.findAllByWorkflowVersionIdOrderByNodeKeyAsc(versionId);
    path.add(targetKey);

    for (NodeDefinition childNode : childNodes) {
      if (!"SUB_WORKFLOW".equalsIgnoreCase(childNode.getNodeType())) {
        continue;
      }
      JsonNode config = childNode.getConfigJson();
      String nextKey = config.path("childWorkflowDefinitionKey").asText(null);
      if (nextKey == null || nextKey.isBlank()) {
        nextKey = config.path("childWorkflowKey").asText(null);
      }
      if (nextKey != null && !nextKey.isBlank()) {
        dfsCheckRecursion(nextKey, rootKey, path, sourceNodeId, issues, visited);
      }
    }

    path.remove(targetKey);
  }
}
