package com.fpt.workflow.definition.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.nodetype.ConfigSchemaMigrator;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit draft-config schema upgrade (P2-07). Published snapshots are immutable and remain
 * executable under their historical schema version; this service only upgrades the configuration
 * of an editable Draft, version by version, through the registered deterministic migrator chain.
 * A version hop without a registered migrator is rejected instead of guessed.
 */
@Service
public class WorkflowConfigSchemaUpgradeService {

  /** Explicit migrator chain: key "NODETYPE:vFrom" → migrator targeting vFrom+1. */
  private final Map<String, ConfigSchemaMigrator> migrators = new ConcurrentHashMap<>();

  private final WorkflowVersionRepository versionRepository;
  private final NodeDefinitionRepository nodeDefinitionRepository;
  private final NodeTypeRegistry nodeTypeRegistry;

  public WorkflowConfigSchemaUpgradeService(
      WorkflowVersionRepository versionRepository,
      NodeDefinitionRepository nodeDefinitionRepository,
      NodeTypeRegistry nodeTypeRegistry) {
    this.versionRepository = Objects.requireNonNull(versionRepository, "versionRepository");
    this.nodeDefinitionRepository =
        Objects.requireNonNull(nodeDefinitionRepository, "nodeDefinitionRepository");
    this.nodeTypeRegistry = Objects.requireNonNull(nodeTypeRegistry, "nodeTypeRegistry");
  }

  /** Registers a deterministic single-hop migrator (platform extension point). */
  public void registerMigrator(String nodeTypeName, int fromVersion, ConfigSchemaMigrator migrator) {
    Objects.requireNonNull(nodeTypeName, "nodeTypeName");
    Objects.requireNonNull(migrator, "migrator");
    migrators.put(nodeTypeName.toUpperCase(java.util.Locale.ROOT) + ":v" + fromVersion, migrator);
  }

  /**
   * Upgrades every node in the Draft to the current config schema version. Draft-only: published
   * versions cannot be upgraded in place. If all nodes are already current this is a no-op that
   * still verifies the optimistic version.
   */
  @Transactional
  public UpgradeResult upgradeDraftToCurrentSchema(
      UUID workflowVersionId, ExpectedVersion expectedVersion, long expectedRevision) {
    WorkflowVersion version =
        versionRepository
            .findById(workflowVersionId)
            .orElseThrow(
                () ->
                    new CommandConflictException(
                        "WORKFLOW_VERSION_NOT_FOUND", "WorkflowVersion was not found"));
    OptimisticVersionGuard.requireMatch(
        new com.fpt.workflow.shared.domain.AggregateVersion(version.getLockVersion()),
        expectedVersion);
    try {
      version.recordGraphMutation(expectedRevision);
    } catch (com.fpt.workflow.definition.domain.StaleDraftRevisionException ex) {
      throw new CommandConflictException("WORKFLOW_DRAFT_REVISION_CONFLICT", ex.getMessage());
    } catch (IllegalStateException ex) {
      throw new CommandConflictException("WORKFLOW_VERSION_NOT_EDITABLE", ex.getMessage());
    }

    int upgraded = 0;
    List<NodeDefinition> nodes = nodeDefinitionRepository.findAllByWorkflowVersionIdOrderByNodeKeyAsc(workflowVersionId);
    for (NodeDefinition node : nodes) {
      NodeType type = parseType(node.getNodeType());
      NodeTypeManifest manifest =
          nodeTypeRegistry.find(type).orElseThrow();
      int from = node.getConfigSchemaVersion();
      if (from >= manifest.currentConfigSchemaVersion()) {
        continue;
      }
      ObjectNode config =
          node.getConfigJson() != null && node.getConfigJson().isObject()
              ? (ObjectNode) node.getConfigJson().deepCopy()
              : new com.fasterxml.jackson.databind.node.JsonNodeFactory(false).objectNode();
      int current = from;
      while (current < manifest.currentConfigSchemaVersion()) {
        ConfigSchemaMigrator migrator =
            migrators.get(type.name() + ":v" + current);
        if (migrator == null) {
          throw new CommandConflictException(
              "WORKFLOW_CONFIG_SCHEMA_MIGRATION_MISSING",
              "No registered migrator upgrades "
                  + type
                  + " config from schema v"
                  + current
                  + " to v"
                  + (current + 1));
        }
        config = migrator.migrate(config);
        current = migrator.toVersion();
      }
      node.update(
          node.getNodeType(),
          node.getName(),
          node.getDescription(),
          manifest.currentConfigSchemaVersion(),
          config,
          node.getInputSchemaJson(),
          node.getOutputSchemaJson(),
          node.getPositionJson());
      nodeDefinitionRepository.save(node);
      upgraded++;
    }
    versionRepository.saveAndFlush(version);
    return new UpgradeResult(workflowVersionId, upgraded);
  }

  private NodeType parseType(String raw) {
    try {
      return NodeType.valueOf(raw);
    } catch (IllegalArgumentException ex) {
      throw new CommandConflictException(
          "WORKFLOW_NODE_TYPE_UNKNOWN", "Unknown node type: " + raw);
    }
  }

  public record UpgradeResult(UUID workflowVersionId, int upgradedNodeCount) {}
}
