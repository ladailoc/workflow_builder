package com.fpt.workflow.definition.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.connector.domain.ConnectorActionVersion;
import com.fpt.workflow.connector.domain.ConnectorDefinition;
import com.fpt.workflow.connector.service.ConnectorRegistry;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.ValidationSeverity;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.engine.DynamicFormEngine;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.engine.FormValidationPhase;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.resolver.expression.Expression;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.resolver.expression.ExpressionScope;
import com.fpt.workflow.resolver.expression.SafeExpressionEngine;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import com.fpt.workflow.shared.domain.value.CanonicalSchema;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Ordered static compiler. It never creates runtime executions or evaluates routes. */
@Component
public class WorkflowValidationCompiler {

  private static final Set<String> VALID_PARTICIPANT_TYPES =
      Set.of(
          "FIXED_USER",
          "ROLE_MEMBERS",
          "GROUP_MEMBERS",
          "MANAGER_OF",
          "HEAD_OF_UNIT",
          "REQUEST_FIELD",
          "PREVIOUS_PARTICIPANT",
          "NODE_OUTPUT",
          "EXPRESSION",
          "CREATOR",
          "ITEM_USER",
          "ITEM_MANAGER");

  private static final Set<String> VALID_SLA_ACTIONS =
      Set.of("ESCALATE", "EXPIRE", "AUTO_EXPIRE", "NOTIFY", "REASSIGN");

  /** Platform cap for SYSTEM_ACTION outbound timeout (§12.6 bounded external I/O). */
  private static final long MAX_SYSTEM_ACTION_TIMEOUT_MS = 120_000L;

  private static final Pattern CROSS_NODE_PATTERN =
      Pattern.compile("nodes\\.([A-Za-z0-9_-]+)\\.(?:latest\\.)?output(?:\\.([A-Za-z0-9_.-]+))?");

  private final NodeTypeRegistry nodeTypeRegistry;
  private final DynamicFormEngine formEngine;
  private final SafeExpressionEngine expressionEngine;
  private final CanonicalDefinitionJson canonicalJson;
  private final ObjectMapper objectMapper;
  private final ControlledCycleAnalyzer cycleAnalyzer;
  private final ConnectorRegistry connectorRegistry;
  private final ActorContextProvider actorContextProvider;
  private final SubWorkflowRecursionValidator recursionValidator;
  private final com.fpt.workflow.form.repository.FormVersionRepository formVersionRepository;
  private final com.fpt.workflow.definition.repository.WorkflowInputDefinitionRepository workflowInputRepository;
  private final com.fpt.workflow.definition.repository.WorkflowStateDefinitionRepository workflowStateRepository;

  @Autowired
  public WorkflowValidationCompiler(
      NodeTypeRegistry nodeTypeRegistry,
      DynamicFormEngine formEngine,
      SafeExpressionEngine expressionEngine,
      CanonicalDefinitionJson canonicalJson,
      ObjectMapper objectMapper,
      ControlledCycleAnalyzer cycleAnalyzer,
      @Autowired(required = false) ConnectorRegistry connectorRegistry,
      @Autowired(required = false) ActorContextProvider actorContextProvider,
      @Autowired(required = false) SubWorkflowRecursionValidator recursionValidator,
      @Autowired(required = false)
          com.fpt.workflow.form.repository.FormVersionRepository formVersionRepository,
      @Autowired(required = false)
          com.fpt.workflow.definition.repository.WorkflowInputDefinitionRepository
              workflowInputRepository,
      @Autowired(required = false)
          com.fpt.workflow.definition.repository.WorkflowStateDefinitionRepository
              workflowStateRepository) {
    this.nodeTypeRegistry = nodeTypeRegistry;
    this.formEngine = formEngine;
    this.expressionEngine = expressionEngine;
    this.canonicalJson = canonicalJson;
    this.objectMapper = objectMapper;
    this.cycleAnalyzer = cycleAnalyzer;
    this.connectorRegistry = connectorRegistry;
    this.actorContextProvider = actorContextProvider;
    this.recursionValidator = recursionValidator;
    this.formVersionRepository = formVersionRepository;
    this.workflowInputRepository = workflowInputRepository;
    this.workflowStateRepository = workflowStateRepository;
  }

  public WorkflowValidationCompiler(
      NodeTypeRegistry nodeTypeRegistry,
      DynamicFormEngine formEngine,
      SafeExpressionEngine expressionEngine,
      CanonicalDefinitionJson canonicalJson,
      ObjectMapper objectMapper,
      ControlledCycleAnalyzer cycleAnalyzer) {
    this(
        nodeTypeRegistry,
        formEngine,
        expressionEngine,
        canonicalJson,
        objectMapper,
        cycleAnalyzer,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public ValidationCompilation compile(ValidationDefinition definition) {
    List<ValidationStage> executedStages = new ArrayList<>();
    List<CompilerIssue> issues = new ArrayList<>();
    Map<UUID, NodeDefinition> nodesById = new LinkedHashMap<>();
    definition.nodes().forEach(node -> nodesById.put(node.getId(), node));

    Map<UUID, List<EdgeDefinition>> outgoing = new HashMap<>();
    Map<UUID, List<EdgeDefinition>> incoming = new HashMap<>();
    for (EdgeDefinition edge : definition.edges()) {
      outgoing.computeIfAbsent(edge.getSourceNodeId(), ignored -> new ArrayList<>()).add(edge);
      incoming.computeIfAbsent(edge.getTargetNodeId(), ignored -> new ArrayList<>()).add(edge);
    }

    // Stage 1: DEFINITION_SCHEMA
    validateDefinitionSchema(definition, issues);
    executedStages.add(ValidationStage.DEFINITION_SCHEMA);

    // Stage 2: NODE_CONFIG
    Map<UUID, NodeTypeManifest> manifests = validateNodeConfigs(definition, issues);
    executedStages.add(ValidationStage.NODE_CONFIG);

    // Stage 3: REFERENCE_PATH_TYPE
    ExpressionSchema expressionSchema = buildExpressionSchema(definition, manifests, issues);
    validateExpressions(definition, expressionSchema, issues);
    executedStages.add(ValidationStage.REFERENCE_PATH_TYPE);

    // Stage 4: GRAPH_STRUCTURE
    validateGraphStructure(definition, nodesById, manifests, outgoing, incoming, issues);
    executedStages.add(ValidationStage.GRAPH_STRUCTURE);

    // Stage 5: NODE_SEMANTICS
    validateNodeSemantics(definition, issues);
    executedStages.add(ValidationStage.NODE_SEMANTICS);

    // Stage 6: ROUTING
    validateRouting(outgoing, nodesById, issues);
    executedStages.add(ValidationStage.ROUTING);

    // Stage 7: PARTICIPANT_TASK
    validateParticipantTasks(definition, issues);
    executedStages.add(ValidationStage.PARTICIPANT_TASK);

    // Stage 8: MULTI_INSTANCE
    validateMultiInstance(definition, issues);
    executedStages.add(ValidationStage.MULTI_INSTANCE);

    // Stage 9: PARALLEL_JOIN_TOKEN_SCOPE
    validateParallelJoinTokenScope(definition, incoming, nodesById, issues);
    executedStages.add(ValidationStage.PARALLEL_JOIN_TOKEN_SCOPE);

    // Stage 10: REWORK
    validateRework(definition, issues);
    executedStages.add(ValidationStage.REWORK);

    // Stage 11: FORM_VARIABLE
    validateFormVariables(definition, expressionSchema, issues);
    executedStages.add(ValidationStage.FORM_VARIABLE);

    // Stage 12: SLA_CALENDAR
    validateSlaCalendar(definition, issues);
    executedStages.add(ValidationStage.SLA_CALENDAR);

    // Stage 13: INTEGRATION_CONNECTOR
    validateIntegrationConnectors(definition, issues);
    executedStages.add(ValidationStage.INTEGRATION_CONNECTOR);

    // Stage 14: SUBWORKFLOW
    validateSubworkflows(definition, issues);
    executedStages.add(ValidationStage.SUBWORKFLOW);

    // Stage 15: PERMISSION_SECURITY
    validatePermissionSecurity(definition, issues);
    executedStages.add(ValidationStage.PERMISSION_SECURITY);

    // Stage 16: VERSION_REVISION
    validateVersionRevision(definition, issues);
    executedStages.add(ValidationStage.VERSION_REVISION);

    // Stage 17: CROSS_NODE_EXECUTION
    validateCrossNodeExecution(definition, nodesById, outgoing, issues);
    executedStages.add(ValidationStage.CROSS_NODE_EXECUTION);

    // Stage 18: WORKFLOW_CONTRACT (v2.4.1: typed inputs.*, declared states, pinned task forms)
    validateWorkflowContract(definition, expressionSchema, issues);
    executedStages.add(ValidationStage.WORKFLOW_CONTRACT);

    JsonNode snapshot = canonicalJson.compile(definition);
    return new ValidationCompilation(
        definition.version().getId(),
        definition.version().getRevision(),
        canonicalJson.checksum(snapshot),
        executedStages,
        issues);
  }

  // --- STAGE 1: DEFINITION_SCHEMA ---
  private void validateDefinitionSchema(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    WorkflowVersion version = definition.version();
    if (version == null) {
      issues.add(
          new CompilerIssue(
              "VERSION_REQUIRED",
              ValidationSeverity.ERROR,
              "WORKFLOW_VERSION",
              UUID.randomUUID(),
              "/version",
              "Workflow version is required",
              "Provide a workflow version",
              null));
      return;
    }
    if (version.getVersionNo() < 1) {
      issues.add(
          new CompilerIssue(
              "INVALID_VERSION_NUMBER",
              ValidationSeverity.ERROR,
              "WORKFLOW_VERSION",
              version.getId(),
              "/versionNo",
              "Version number must be >= 1",
              "Use positive version number",
              null));
    }
    if (version.getRevision() < 0) {
      issues.add(
          new CompilerIssue(
              "INVALID_VERSION_REVISION",
              ValidationSeverity.ERROR,
              "WORKFLOW_VERSION",
              version.getId(),
              "/revision",
              "Revision must be >= 0",
              "Use non-negative revision",
              null));
    }
    for (NodeDefinition node : definition.nodes()) {
      if (node.getDescription() != null && node.getDescription().isBlank()) {
        issues.add(
            new CompilerIssue(
                "NODE_DESCRIPTION_EMPTY",
                ValidationSeverity.INFO,
                "NODE",
                node.getId(),
                "/description",
                "Node description is blank",
                "Provide a descriptive summary of the node behavior",
                null));
      }
    }
  }

  // --- STAGE 2: NODE_CONFIG ---
  private Map<UUID, NodeTypeManifest> validateNodeConfigs(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    Map<UUID, NodeTypeManifest> manifests = new HashMap<>();
    for (NodeDefinition node : definition.nodes()) {
      NodeType type;
      try {
        type = NodeType.valueOf(node.getNodeType());
      } catch (IllegalArgumentException exception) {
        issue(
            issues,
            "UNKNOWN_NODE_TYPE",
            node,
            "/nodeType",
            "Unknown node type",
            "Register a NodeTypeProvider");
        continue;
      }
      NodeTypeManifest manifest = nodeTypeRegistry.find(type).orElse(null);
      if (manifest == null) {
        issue(
            issues,
            "UNKNOWN_NODE_TYPE",
            node,
            "/nodeType",
            "Node type is not registered",
            "Register a NodeTypeProvider");
        continue;
      }
      manifests.put(node.getId(), manifest);
      manifest
          .validate(node.getNodeKey(), node.getConfigSchemaVersion(), node.getConfigJson())
          .forEach(
              validationIssue ->
                  issues.add(
                      new CompilerIssue(
                          validationIssue.code().replace("NODE.", "NODE_"),
                          validationIssue.blocking()
                              ? ValidationSeverity.ERROR
                              : ValidationSeverity.WARNING,
                          "NODE",
                          node.getId(),
                          validationIssue.fieldPath(),
                          validationIssue.message(),
                          "Conform to the registered strict node manifest",
                          null)));
    }
    return manifests;
  }

  // --- STAGE 4: GRAPH_STRUCTURE ---
  private void validateGraphStructure(
      ValidationDefinition definition,
      Map<UUID, NodeDefinition> nodes,
      Map<UUID, NodeTypeManifest> manifests,
      Map<UUID, List<EdgeDefinition>> outgoing,
      Map<UUID, List<EdgeDefinition>> incoming,
      List<CompilerIssue> issues) {
    List<NodeDefinition> starts =
        definition.nodes().stream().filter(node -> node.getNodeType().equals("START")).toList();
    List<NodeDefinition> ends =
        definition.nodes().stream().filter(node -> node.getNodeType().equals("END")).toList();
    if (starts.isEmpty())
      issue(
          issues,
          "NO_START",
          definition.version(),
          "/nodes",
          "Exactly one START is required",
          "Add one START node");
    if (starts.size() > 1)
      issue(
          issues,
          "MULTIPLE_START",
          definition.version(),
          "/nodes",
          "Exactly one START is required",
          "Keep one START node");
    if (ends.isEmpty())
      issue(
          issues,
          "NO_END",
          definition.version(),
          "/nodes",
          "At least one END is required",
          "Add an END node");

    for (EdgeDefinition edge : definition.edges()) {
      if (!nodes.containsKey(edge.getSourceNodeId())) {
        issues.add(
            new CompilerIssue(
                "DANGLING_EDGE",
                ValidationSeverity.ERROR,
                "EDGE",
                edge.getId(),
                "/sourceNodeId",
                "Edge source is missing",
                "Select a node in this version",
                null));
      }
      if (!nodes.containsKey(edge.getTargetNodeId())) {
        issues.add(
            new CompilerIssue(
                "MISSING_TARGET",
                ValidationSeverity.ERROR,
                "EDGE",
                edge.getId(),
                "/targetNodeId",
                "Edge target is missing",
                "Select a node in this version",
                null));
      }
      NodeTypeManifest manifest = manifests.get(edge.getSourceNodeId());
      if (manifest != null && !manifest.outputPorts().contains(edge.getSourcePort())) {
        issues.add(
            new CompilerIssue(
                "INVALID_OUTPUT_PORT",
                ValidationSeverity.ERROR,
                "EDGE",
                edge.getId(),
                "/sourcePort",
                "Source port is not declared by the node manifest",
                "Use a manifest output port",
                null));
      }
    }
    starts.forEach(
        start -> {
          if (!incoming.getOrDefault(start.getId(), List.of()).isEmpty())
            issue(
                issues,
                "START_HAS_INCOMING",
                start,
                "/incoming",
                "START cannot have incoming edges",
                "Remove incoming edges");
        });
    ends.forEach(
        end -> {
          if (!outgoing.getOrDefault(end.getId(), List.of()).isEmpty())
            issue(
                issues,
                "END_HAS_OUTGOING",
                end,
                "/outgoing",
                "END cannot have outgoing edges",
                "Remove outgoing edges");
        });

    Set<UUID> reachable = reachable(starts, outgoing, nodes);
    definition
        .nodes()
        .forEach(
            node -> {
              if (!reachable.contains(node.getId()))
                issue(
                    issues,
                    "UNREACHABLE_NODE",
                    node,
                    "/id",
                    "Node is unreachable from START",
                    "Connect it from a reachable node");
              if (reachable.contains(node.getId())
                  && !node.getNodeType().equals("END")
                  && outgoing.getOrDefault(node.getId(), List.of()).isEmpty()) {
                issue(
                    issues,
                    "NONTERMINAL_DEAD_END",
                    node,
                    "/outgoing",
                    "Reachable nonterminal node is a dead-end",
                    "Route it to another node");
              }
              NodeTypeManifest manifest = manifests.get(node.getId());
              if (manifest != null && !node.getNodeType().equals("END")) {
                Set<String> handled = new HashSet<>();
                outgoing
                    .getOrDefault(node.getId(), List.of())
                    .forEach(edge -> handled.add(edge.getSourcePort()));
                manifest.outputPorts().stream()
                    .filter(port -> !handled.contains(port))
                    .forEach(
                        port ->
                            issue(
                                issues,
                                "UNHANDLED_PORT",
                                node,
                                "/outputPorts/" + port,
                                "Active output port has no route",
                                "Add an edge or explicit terminal policy"));
              }
            });
  }

  // --- STAGE 5: NODE_SEMANTICS ---
  private void validateNodeSemantics(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    Set<String> seenKeys = new HashSet<>();
    for (NodeDefinition node : definition.nodes()) {
      if (node.getNodeKey() == null || node.getNodeKey().isBlank()) {
        issue(
            issues,
            "NODE_KEY_REQUIRED",
            node,
            "/nodeKey",
            "Node key is required",
            "Specify a unique node key");
      } else if (!seenKeys.add(node.getNodeKey())) {
        issue(
            issues,
            "DUPLICATE_NODE_KEY",
            node,
            "/nodeKey",
            "Duplicate node key: " + node.getNodeKey(),
            "Ensure node keys are unique within the workflow");
      }
      if (node.getName() != null && isGenericName(node.getName())) {
        issues.add(
            new CompilerIssue(
                "GENERIC_NODE_NAME",
                ValidationSeverity.INFO,
                "NODE",
                node.getId(),
                "/name",
                "Node has a generic name: " + node.getName(),
                "Provide a descriptive node name",
                null));
      }
    }
  }

  private boolean isGenericName(String name) {
    if (name == null) return false;
    String trimmed = name.trim().toLowerCase();
    return trimmed.equals("untitled")
        || trimmed.equals("generic node")
        || trimmed.equals("new node")
        || trimmed.equals("placeholder");
  }

  // --- STAGE 7: PARTICIPANT_TASK ---
  private void validateParticipantTasks(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    for (NodeDefinition node : definition.nodes()) {
      boolean isHumanTask =
          "REVIEW".equalsIgnoreCase(node.getNodeType())
              || "APPROVAL".equalsIgnoreCase(node.getNodeType())
              || "TASK".equalsIgnoreCase(node.getNodeType());

      JsonNode config = node.getConfigJson();
      JsonNode participant = null;
      if (config != null) {
        if (config.has("participant")) {
          participant = config.get("participant");
        } else if (config.has("participantResolver")) {
          participant = config.get("participantResolver");
        }
      }

      if (isHumanTask && (participant == null || participant.isNull())) {
        issues.add(
            new CompilerIssue(
                "TASK_PARTICIPANT_REQUIRED",
                ValidationSeverity.ERROR,
                "NODE",
                node.getId(),
                "/config/participant",
                "Interactive task node requires a participant resolver configuration",
                "Specify a participant resolver",
                null));
        continue;
      }

      if (participant != null && !participant.isNull()) {
        if (!participant.isObject()) {
          issues.add(
              new CompilerIssue(
                  "INVALID_PARTICIPANT_CONFIG",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  "/config/participant",
                  "Participant config must be an object",
                  "Specify an object with type and parameters",
                  null));
          continue;
        }

        String typeStr = participant.path("type").asText(null);
        if (typeStr == null || typeStr.isBlank()) {
          issues.add(
              new CompilerIssue(
                  "PARTICIPANT_TYPE_REQUIRED",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  "/config/participant/type",
                  "Participant resolver requires 'type'",
                  "Specify a valid participant type",
                  null));
        } else if (!VALID_PARTICIPANT_TYPES.contains(typeStr.toUpperCase())) {
          issues.add(
              new CompilerIssue(
                  "UNKNOWN_PARTICIPANT_TYPE",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  "/config/participant/type",
                  "Unknown participant type: " + typeStr,
                  "Use one of: " + VALID_PARTICIPANT_TYPES,
                  null));
        } else {
          validateParticipantParams(
              node, participant, typeStr.toUpperCase(), "/config/participant", issues);
        }

        if (participant.path("ackRequired").asBoolean(false)) {
          issues.add(
              new CompilerIssue(
                  "PARTICIPANT_RESOLUTION_ACK_REQUIRED",
                  ValidationSeverity.ACK_REQUIRED_WARNING,
                  "NODE",
                  node.getId(),
                  "/config/participant",
                  "Participant resolution requires operator acknowledgement",
                  "Acknowledge warning before publish",
                  null));
        }

        if (participant.has("fallback") && !participant.get("fallback").isNull()) {
          JsonNode fallback = participant.get("fallback");
          if (fallback.isObject()) {
            String fbType = fallback.path("type").asText(null);
            if (fbType != null && fbType.equalsIgnoreCase(typeStr)) {
              issues.add(
                  new CompilerIssue(
                      "FALLBACK_RESOLVER_IDENTICAL",
                      ValidationSeverity.WARNING,
                      "NODE",
                      node.getId(),
                      "/config/participant/fallback",
                      "Fallback resolver has identical type to primary resolver",
                      "Use an alternative fallback strategy",
                      null));
            }
          }
        }
      }

      if (config != null && config.has("allowedActions")) {
        JsonNode actions = config.get("allowedActions");
        if (actions.isArray() && actions.isEmpty()) {
          issues.add(
              new CompilerIssue(
                  "ALLOWED_ACTIONS_EMPTY",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  "/config/allowedActions",
                  "allowedActions cannot be empty when declared",
                  "Specify at least one action or remove the field",
                  null));
        }
      }
    }
  }

  private void validateParticipantParams(
      NodeDefinition node, JsonNode p, String type, String path, List<CompilerIssue> issues) {
    switch (type) {
      case "FIXED_USER" -> {
        if (!p.hasNonNull("userId") || p.get("userId").asText().isBlank()) {
          issues.add(
              new CompilerIssue(
                  "FIXED_USER_ID_REQUIRED",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  path + "/userId",
                  "FIXED_USER resolver requires userId",
                  "Provide a valid userId UUID",
                  null));
        }
      }
      case "ROLE_MEMBERS" -> {
        boolean hasRole =
            (p.hasNonNull("role") && !p.get("role").asText().isBlank())
                || (p.hasNonNull("roleKey") && !p.get("roleKey").asText().isBlank());
        if (!hasRole) {
          issues.add(
              new CompilerIssue(
                  "ROLE_MEMBERS_KEY_REQUIRED",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  path + "/role",
                  "ROLE_MEMBERS resolver requires role or roleKey",
                  "Provide a valid role identifier",
                  null));
        }
        if (p.path("warnDynamicResolver").asBoolean(false)) {
          issues.add(
              new CompilerIssue(
                  "DYNAMIC_RESOLVER_RUNTIME_CHECK",
                  ValidationSeverity.WARNING,
                  "NODE",
                  node.getId(),
                  path,
                  "Dynamic role resolver may resolve to empty set at runtime; ensure fallback is configured",
                  "Configure a fallback resolver",
                  null));
        }
      }
      case "GROUP_MEMBERS" -> {
        boolean hasGroup =
            (p.hasNonNull("groupId") && !p.get("groupId").asText().isBlank())
                || (p.hasNonNull("groupKey") && !p.get("groupKey").asText().isBlank());
        if (!hasGroup) {
          issues.add(
              new CompilerIssue(
                  "GROUP_MEMBERS_KEY_REQUIRED",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  path + "/groupId",
                  "GROUP_MEMBERS resolver requires groupId or groupKey",
                  "Provide a valid group identifier",
                  null));
        }
        if (p.path("warnDynamicResolver").asBoolean(false)) {
          issues.add(
              new CompilerIssue(
                  "DYNAMIC_RESOLVER_RUNTIME_CHECK",
                  ValidationSeverity.WARNING,
                  "NODE",
                  node.getId(),
                  path,
                  "Dynamic group resolver may resolve to empty set at runtime; ensure fallback is configured",
                  "Configure a fallback resolver",
                  null));
        }
      }
      case "REQUEST_FIELD" -> {
        boolean hasField =
            (p.hasNonNull("fieldKey") && !p.get("fieldKey").asText().isBlank())
                || (p.hasNonNull("fieldPath") && !p.get("fieldPath").asText().isBlank())
                || (p.hasNonNull("field") && !p.get("field").asText().isBlank());
        if (!hasField) {
          issues.add(
              new CompilerIssue(
                  "REQUEST_FIELD_KEY_REQUIRED",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  path + "/fieldKey",
                  "REQUEST_FIELD resolver requires fieldKey",
                  "Provide a valid request field key",
                  null));
        }
      }
      case "PREVIOUS_PARTICIPANT" -> {
        if (!p.hasNonNull("nodeKey") || p.get("nodeKey").asText().isBlank()) {
          issues.add(
              new CompilerIssue(
                  "PREVIOUS_PARTICIPANT_NODE_KEY_REQUIRED",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  path + "/nodeKey",
                  "PREVIOUS_PARTICIPANT resolver requires nodeKey",
                  "Provide target node key",
                  null));
        }
      }
      case "EXPRESSION" -> {
        boolean hasExpr =
            (p.hasNonNull("expression") && !p.get("expression").asText().isBlank())
                || (p.hasNonNull("value") && !p.get("value").asText().isBlank());
        if (!hasExpr) {
          issues.add(
              new CompilerIssue(
                  "EXPRESSION_REQUIRED",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  path + "/expression",
                  "EXPRESSION resolver requires non-blank expression",
                  "Provide a valid expression",
                  null));
        }
      }
    }
  }

  // --- STAGE 8: MULTI_INSTANCE ---
  private void validateMultiInstance(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    for (NodeDefinition node : definition.nodes()) {
      if (node.getConfigJson() == null || !node.getConfigJson().hasNonNull("multiInstance")) {
        continue;
      }
      validateMultiInstanceNode(node, issues);
    }
  }

  private void validateMultiInstanceNode(NodeDefinition node, List<CompilerIssue> issues) {
    if (node.getConfigJson() == null || !node.getConfigJson().hasNonNull("multiInstance")) {
      return;
    }
    JsonNode miConfig = node.getConfigJson().get("multiInstance");
    if (!miConfig.isObject()) {
      issues.add(
          new CompilerIssue(
              "INVALID_MULTI_INSTANCE_CONFIG",
              ValidationSeverity.ERROR,
              "NODE",
              node.getId(),
              "/config/multiInstance",
              "multiInstance must be an object",
              "Conform to multiInstance schema",
              null));
      return;
    }

    boolean hasCollection =
        (miConfig.hasNonNull("collection") && !miConfig.get("collection").asText().isBlank())
            || (miConfig.hasNonNull("collectionPath")
                && !miConfig.get("collectionPath").asText().isBlank());
    if (!hasCollection) {
      issues.add(
          new CompilerIssue(
              "MULTI_INSTANCE_COLLECTION_REQUIRED",
              ValidationSeverity.ERROR,
              "NODE",
              node.getId(),
              "/config/multiInstance/collection",
              "Multi-instance node requires 'collection' or 'collectionPath'",
              "Specify the collection reference (e.g. ${ticket.data.items})",
              null));
    }

    String policyStr = null;
    if (miConfig.hasNonNull("completionPolicy")) {
      JsonNode policyNode = miConfig.get("completionPolicy");
      if (policyNode.isObject() && policyNode.hasNonNull("type")) {
        policyStr = policyNode.get("type").asText();
      } else if (policyNode.isTextual()) {
        policyStr = policyNode.asText();
      }
    }
    if (policyStr == null || policyStr.isBlank()) {
      policyStr = "ALL";
    }

    boolean isThresholdPolicy =
        "ANY".equalsIgnoreCase(policyStr)
            || "FIRST".equalsIgnoreCase(policyStr)
            || "N_OF_M".equalsIgnoreCase(policyStr)
            || "PERCENTAGE".equalsIgnoreCase(policyStr);

    if (isThresholdPolicy) {
      if (!miConfig.hasNonNull("remainingItemPolicy")
          || miConfig.get("remainingItemPolicy").asText().isBlank()) {
        issues.add(
            new CompilerIssue(
                "MULTI_INSTANCE_REMAINING_ITEM_POLICY_REQUIRED",
                ValidationSeverity.ERROR,
                "NODE",
                node.getId(),
                "/config/multiInstance/remainingItemPolicy",
                "Threshold-based multi-instance completion policy ("
                    + policyStr
                    + ") requires an explicit remainingItemPolicy",
                "Specify remainingItemPolicy as CANCEL_REMAINING or KEEP_RUNNING",
                null));
      } else {
        String remainingPolicy = miConfig.get("remainingItemPolicy").asText();
        if (!"CANCEL_REMAINING".equalsIgnoreCase(remainingPolicy)
            && !"KEEP_RUNNING".equalsIgnoreCase(remainingPolicy)) {
          issues.add(
              new CompilerIssue(
                  "MULTI_INSTANCE_REMAINING_ITEM_POLICY_INVALID",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  "/config/multiInstance/remainingItemPolicy",
                  "Invalid remainingItemPolicy: " + remainingPolicy,
                  "Specify remainingItemPolicy as CANCEL_REMAINING or KEEP_RUNNING",
                  null));
        } else if ("CANCEL_REMAINING".equalsIgnoreCase(remainingPolicy)
            && (miConfig.path("requireAcknowledgement").asBoolean(false)
                || miConfig.path("ackRequired").asBoolean(false))) {
          issues.add(
              new CompilerIssue(
                  "MULTI_INSTANCE_CANCEL_REMAINING_ACK_REQUIRED",
                  ValidationSeverity.ACK_REQUIRED_WARNING,
                  "NODE",
                  node.getId(),
                  "/config/multiInstance/remainingItemPolicy",
                  "Multi-instance node configured with CANCEL_REMAINING will cancel active items upon threshold completion",
                  "Acknowledge warning before publish if this behavior is intended",
                  null));
        }
      }
    }
  }

  // --- STAGE 9: PARALLEL_JOIN_TOKEN_SCOPE ---
  private void validateParallelJoinTokenScope(
      ValidationDefinition definition,
      Map<UUID, List<EdgeDefinition>> incoming,
      Map<UUID, NodeDefinition> nodesById,
      List<CompilerIssue> issues) {
    for (NodeDefinition node : definition.nodes()) {
      if (!"JOIN".equalsIgnoreCase(node.getNodeType())) {
        continue;
      }
      validateJoinNode(node, issues);
    }
  }

  private void validateJoinNode(NodeDefinition node, List<CompilerIssue> issues) {
    if (!"JOIN".equalsIgnoreCase(node.getNodeType())) {
      return;
    }
    JsonNode config = node.getConfigJson();
    if (config == null || !config.isObject()) {
      return;
    }
    String policy = config.path("policy").asText("ALL");
    if ("ANY".equalsIgnoreCase(policy) || "FIRST".equalsIgnoreCase(policy)) {
      if (!config.hasNonNull("remainingBranchPolicy")
          || config.get("remainingBranchPolicy").asText().isBlank()) {
        issues.add(
            new CompilerIssue(
                "JOIN_REMAINING_BRANCH_POLICY_REQUIRED",
                ValidationSeverity.ERROR,
                "NODE",
                node.getId(),
                "/config/remainingBranchPolicy",
                "ANY/FIRST Join gateway requires an explicit remainingBranchPolicy",
                "Specify remainingBranchPolicy as CANCEL_REMAINING or KEEP_RUNNING",
                null));
      } else {
        String remainingBranchPolicy = config.get("remainingBranchPolicy").asText();
        if (!"CANCEL_REMAINING".equalsIgnoreCase(remainingBranchPolicy)
            && !"KEEP_RUNNING".equalsIgnoreCase(remainingBranchPolicy)) {
          issues.add(
              new CompilerIssue(
                  "JOIN_REMAINING_BRANCH_POLICY_INVALID",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  "/config/remainingBranchPolicy",
                  "Invalid remainingBranchPolicy: " + remainingBranchPolicy,
                  "Specify remainingBranchPolicy as CANCEL_REMAINING or KEEP_RUNNING",
                  null));
        } else if ("CANCEL_REMAINING".equalsIgnoreCase(remainingBranchPolicy)
            && (config.path("requireAcknowledgement").asBoolean(false)
                || config.path("ackRequired").asBoolean(false))) {
          issues.add(
              new CompilerIssue(
                  "JOIN_CANCEL_REMAINING_ACK_REQUIRED",
                  ValidationSeverity.ACK_REQUIRED_WARNING,
                  "NODE",
                  node.getId(),
                  "/config/remainingBranchPolicy",
                  "ANY/FIRST Join gateway configured with CANCEL_REMAINING will cancel active sibling branches",
                  "Acknowledge warning before publish if this behavior is intended",
                  null));
        }
      }
    }
  }

  // --- STAGE 10: REWORK ---
  private void validateRework(ValidationDefinition definition, List<CompilerIssue> issues) {
    cycleAnalyzer
        .analyze(definition.nodes(), definition.edges())
        .forEach(
            cycleIssue ->
                issues.add(
                    new CompilerIssue(
                        cycleIssue.code(),
                        ValidationSeverity.ERROR,
                        "EDGE",
                        cycleIssue.resourceId(),
                        cycleIssue.fieldPath(),
                        cycleIssue.message(),
                        "Declare a bounded reworkPolicy with compatible scope",
                        null)));
  }

  // --- STAGE 11: FORM_VARIABLE ---
  private void validateFormVariables(
      ValidationDefinition definition, ExpressionSchema schema, List<CompilerIssue> issues) {
    validateForms(definition, schema, issues);
    Set<String> seenVars = new HashSet<>();
    for (var variable : definition.variables()) {
      if (variable.getKey() == null || !variable.getKey().matches("^[A-Za-z][A-Za-z0-9_]*$")) {
        issues.add(
            new CompilerIssue(
                "INVALID_VARIABLE_KEY",
                ValidationSeverity.ERROR,
                "WORKFLOW_VARIABLE",
                variable.getId(),
                "/key",
                "Variable key must start with a letter and contain only alphanumeric/underscore characters",
                "Use canonical variable naming",
                null));
      } else if (!seenVars.add(variable.getKey())) {
        issues.add(
            new CompilerIssue(
                "DUPLICATE_VARIABLE_KEY",
                ValidationSeverity.ERROR,
                "WORKFLOW_VARIABLE",
                variable.getId(),
                "/key",
                "Duplicate variable key: " + variable.getKey(),
                "Ensure variable keys are unique",
                null));
      }
    }
  }

  // --- STAGE 12: SLA_CALENDAR ---
  private void validateSlaCalendar(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    for (NodeDefinition node : definition.nodes()) {
      JsonNode config = node.getConfigJson();
      if (config == null) continue;

      JsonNode sla = null;
      if (config.has("sla")) sla = config.get("sla");
      else if (config.has("slaConfig")) sla = config.get("slaConfig");

      if (sla != null && !sla.isNull() && sla.isObject()) {
        if (sla.has("durationMinutes")) {
          int duration = sla.get("durationMinutes").asInt(0);
          if (duration <= 0) {
            issues.add(
                new CompilerIssue(
                    "INVALID_SLA_DURATION",
                    ValidationSeverity.ERROR,
                    "NODE",
                    node.getId(),
                    "/config/sla/durationMinutes",
                    "SLA durationMinutes must be greater than 0",
                    "Specify positive duration in minutes",
                    null));
          }
        }
        if (sla.has("timeoutAction") || sla.has("action")) {
          String action =
              sla.has("timeoutAction")
                  ? sla.get("timeoutAction").asText()
                  : sla.get("action").asText();
          if (action != null && !VALID_SLA_ACTIONS.contains(action.toUpperCase())) {
            issues.add(
                new CompilerIssue(
                    "INVALID_SLA_TIMEOUT_ACTION",
                    ValidationSeverity.ERROR,
                    "NODE",
                    node.getId(),
                    "/config/sla/timeoutAction",
                    "Invalid SLA timeout action: " + action,
                    "Use one of: " + VALID_SLA_ACTIONS,
                    null));
          }
        }
        if (sla.has("calendarKey")) {
          String calKey = sla.get("calendarKey").asText();
          if (calKey == null || calKey.isBlank() || !calKey.matches("^[A-Za-z0-9_-]+$")) {
            issues.add(
                new CompilerIssue(
                    "INVALID_SLA_CALENDAR_KEY",
                    ValidationSeverity.ERROR,
                    "NODE",
                    node.getId(),
                    "/config/sla/calendarKey",
                    "Invalid SLA calendarKey: " + calKey,
                    "Use alphanumeric key with underscores/hyphens",
                    null));
          }
        }
      } else if (config.path("warnMissingSla").asBoolean(false)) {
        boolean isHumanTask =
            "REVIEW".equalsIgnoreCase(node.getNodeType())
                || "APPROVAL".equalsIgnoreCase(node.getNodeType())
                || "TASK".equalsIgnoreCase(node.getNodeType());
        if (isHumanTask) {
          issues.add(
              new CompilerIssue(
                  "MISSING_TASK_SLA",
                  ValidationSeverity.WARNING,
                  "NODE",
                  node.getId(),
                  "/config",
                  "Interactive task has no SLA configured; completion time will be unbounded",
                  "Consider configuring an SLA duration and timeout policy",
                  null));
        }
      }
    }
  }

  // --- STAGE 13: INTEGRATION_CONNECTOR ---
  private void validateIntegrationConnectors(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    for (NodeDefinition node : definition.nodes()) {
      if ("SYSTEM_ACTION".equals(node.getNodeType())) {
        validateSystemActionNode(node, issues);
      }
    }
  }

  private void validateSystemActionNode(NodeDefinition node, List<CompilerIssue> issues) {
    if (!"SYSTEM_ACTION".equals(node.getNodeType())) {
      return;
    }
    try {
      ConnectorDefinition.validateNoSecrets(node.getConfigJson());
    } catch (IllegalArgumentException ex) {
      issues.add(
          new CompilerIssue(
              "SECRET_STORAGE_FORBIDDEN",
              ValidationSeverity.ERROR,
              "NODE",
              node.getId(),
              "/config",
              ex.getMessage(),
              "Only store credentialRef; never embed secret values in node config",
              null));
    }

    if (connectorRegistry == null) {
      return;
    }

    JsonNode config = node.getConfigJson();
    String connectorKey = config.path("connectorKey").asText(null);
    String actionKey = config.path("actionKey").asText(null);
    int actionVersion = config.path("actionVersion").asInt(0);

    if (connectorKey == null || actionKey == null || actionVersion < 1) {
      return;
    }

    // P2-05: SYSTEM_ACTION outbound timeout must be bounded and positive (§12.6). External I/O
    // is never executed inside a long transaction, but an unbounded timeout would still block
    // the durable job worker indefinitely.
    if (config.hasNonNull("timeoutMs")) {
      long timeoutMs = config.get("timeoutMs").asLong(-1);
      if (timeoutMs <= 0) {
        issues.add(
            new CompilerIssue(
                "SYSTEM_ACTION_TIMEOUT_INVALID",
                ValidationSeverity.ERROR,
                "NODE",
                node.getId(),
                "/config/timeoutMs",
                "timeoutMs must be a positive number of milliseconds",
                "Set a positive bounded timeout (e.g. 10000)",
                null));
      } else if (timeoutMs > MAX_SYSTEM_ACTION_TIMEOUT_MS) {
        issues.add(
            new CompilerIssue(
                "SYSTEM_ACTION_TIMEOUT_EXCEEDED",
                ValidationSeverity.ERROR,
                "NODE",
                node.getId(),
                "/config/timeoutMs",
                "timeoutMs exceeds the platform maximum of "
                    + MAX_SYSTEM_ACTION_TIMEOUT_MS
                    + " ms",
                "Lower the timeout or split the action",
                null));
      }
    }

    try {
      ConnectorActionVersion version =
          connectorRegistry.requireActionVersion(connectorKey, actionKey, actionVersion);
      ActorContext actor =
          actorContextProvider != null ? actorContextProvider.currentActor().orElse(null) : null;
      if (actor != null) {
        connectorRegistry.validateActionPermission(version, actor);
      }
    } catch (AccessDeniedException ex) {
      issues.add(
          new CompilerIssue(
              "CONNECTOR_ACTION_PERMISSION_DENIED",
              ValidationSeverity.ERROR,
              "NODE",
              node.getId(),
              "/config/actionKey",
              ex.getMessage(),
              "Request access or select an allowlisted connector action version",
              null));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      issues.add(
          new CompilerIssue(
              "INVALID_CONNECTOR_ACTION_BINDING",
              ValidationSeverity.ERROR,
              "NODE",
              node.getId(),
              "/config",
              ex.getMessage(),
              "Ensure the connector, action, and version exist and are active/published",
              null));
    }
  }

  // --- STAGE 14: SUBWORKFLOW ---
  private void validateSubworkflows(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    if (recursionValidator != null) {
      recursionValidator.validate(definition, issues);
    }
    for (NodeDefinition node : definition.nodes()) {
      if ("SUB_WORKFLOW".equalsIgnoreCase(node.getNodeType())) {
        JsonNode cfg = node.getConfigJson();
        if (cfg != null && cfg.path("childSuspended").asBoolean(false)) {
          issues.add(
              new CompilerIssue(
                  "CHILD_WORKFLOW_SUSPENDED",
                  ValidationSeverity.ACK_REQUIRED_WARNING,
                  "NODE",
                  node.getId(),
                  "/config/workflowKey",
                  "Child workflow is currently suspended",
                  "Acknowledge child suspension before publishing",
                  null));
        }
      }
    }
  }

  // --- STAGE 15: PERMISSION_SECURITY ---
  private void validatePermissionSecurity(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    WorkflowVersion version = definition.version();
    if (version != null && version.getDefinitionId() == null) {
      issues.add(
          new CompilerIssue(
              "WORKFLOW_DEFINITION_ID_REQUIRED",
              ValidationSeverity.ERROR,
              "WORKFLOW_VERSION",
              version.getId(),
              "/definitionId",
              "Workflow definition ID is required",
              "Associate with a valid workflow definition",
              null));
    }
  }

  // --- STAGE 16: VERSION_REVISION ---
  /**
   * Version/revision integrity stage. Validates version identity and revision invariants for any
   * version state (Draft re-validation before publish, plus re-validation of published packages
   * for monitoring/diff). Draft-only enforcement is a publish-gate concern handled by the publish
   * flow, not a compilation error.
   */
  private void validateVersionRevision(
      ValidationDefinition definition, List<CompilerIssue> issues) {
    WorkflowVersion version = definition.version();
    if (version == null) {
      return;
    }
    if (version.getVersionNo() < 1) {
      issues.add(
          new CompilerIssue(
              "WORKFLOW_VERSION_NUMBER_INVALID",
              ValidationSeverity.ERROR,
              "WORKFLOW_VERSION",
              version.getId(),
              "/versionNo",
              "Workflow version number must be positive",
              "Recreate the version",
              null));
    }
    if (version.getRevision() < 0) {
      issues.add(
          new CompilerIssue(
              "WORKFLOW_VERSION_REVISION_INVALID",
              ValidationSeverity.ERROR,
              "WORKFLOW_VERSION",
              version.getId(),
              "/revision",
              "Workflow version revision must not be negative",
              "Recreate the version",
              null));
    }
    if (version.getStatus() == WorkflowVersionStatus.PUBLISHED
        && (version.getChecksum() == null || version.getChecksum().isBlank())) {
      issues.add(
          new CompilerIssue(
              "WORKFLOW_VERSION_CHECKSUM_MISSING",
              ValidationSeverity.ERROR,
              "WORKFLOW_VERSION",
              version.getId(),
              "/checksum",
              "Published versions must carry an execution package checksum",
              "Republish the version",
              null));
    }
    if (version.getCreatedAt() != null && version.getPublishedAt() != null) {
      if (version.getPublishedAt().isBefore(version.getCreatedAt())) {
        issues.add(
            new CompilerIssue(
                "WORKFLOW_VERSION_PUBLISHED_BEFORE_CREATED",
                ValidationSeverity.ERROR,
                "WORKFLOW_VERSION",
                version.getId(),
                "/publishedAt",
                "Published timestamp precedes creation timestamp",
                "Audit the version history",
                null));
      }
    }
  }

  /** P2-22: participant/expression references to CURRENT_ITEM outside any MultiInstance scope. */
  private static final java.util.regex.Pattern CURRENT_ITEM_PATTERN =
      java.util.regex.Pattern.compile(
          "(?i)\\bCURRENT_ITEM\\b|\\$\\{\\s*currentItem");

  // --- STAGE 17: CROSS_NODE_EXECUTION ---
  private void validateCrossNodeExecution(
      ValidationDefinition definition,
      Map<UUID, NodeDefinition> nodesById,
      Map<UUID, List<EdgeDefinition>> outgoing,
      List<CompilerIssue> issues) {
    Map<String, NodeDefinition> nodesByKey = new HashMap<>();
    nodesById.values().forEach(n -> nodesByKey.put(n.getNodeKey(), n));

    // P2-22 (§8.7): CURRENT_ITEM resolver/expression is invalid where no MultiInstance
    // current-item context can exist — surface at publish validation rather than runtime.
    for (NodeDefinition node : definition.nodes()) {
      JsonNode config = node.getConfigJson();
      if (config == null) continue;
      boolean hasMultiInstance = config.hasNonNull("multiInstance");
      if (!hasMultiInstance && CURRENT_ITEM_PATTERN.matcher(config.toString()).find()) {
        issues.add(
            new CompilerIssue(
                "CURRENT_ITEM_FOREIGN_SCOPE",
                ValidationSeverity.ERROR,
                "NODE",
                node.getId(),
                "/config",
                "Node references CURRENT_ITEM outside any MultiInstance/current-item context",
                "Move CURRENT_ITEM usage into a MultiInstance node or remove it",
                null));
      }
    }

    for (NodeDefinition node : definition.nodes()) {
      JsonNode config = node.getConfigJson();
      if (config == null) continue;
      String jsonText = config.toString();
      Matcher matcher = CROSS_NODE_PATTERN.matcher(jsonText);
      while (matcher.find()) {
        String targetKey = matcher.group(1);
        if (targetKey.equals(node.getNodeKey())) {
          issues.add(
              new CompilerIssue(
                  "CROSS_NODE_SELF_REFERENCE",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  "/config",
                  "Node cannot reference its own output: " + targetKey,
                  "Reference an upstream node output",
                  null));
          continue;
        }
        NodeDefinition targetNode = nodesByKey.get(targetKey);
        if (targetNode == null) {
          issues.add(
              new CompilerIssue(
                  "CROSS_NODE_TARGET_NOT_FOUND",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  "/config",
                  "Referenced node output does not exist: " + targetKey,
                  "Reference a known upstream node in this workflow",
                  null));
          continue;
        }
        if (isReachable(node.getId(), targetNode.getId(), outgoing)) {
          issues.add(
              new CompilerIssue(
                  "CROSS_NODE_EXECUTION_ORDER_INVALID",
                  ValidationSeverity.ERROR,
                  "NODE",
                  node.getId(),
                  "/config",
                  "Node references output of downstream node '"
                      + targetKey
                      + "' which has not executed yet",
                  "Ensure referenced node executes before this node in the graph",
                  null));
        }
      }
    }
  }

  private boolean isReachable(UUID from, UUID to, Map<UUID, List<EdgeDefinition>> outgoing) {
    Set<UUID> visited = new HashSet<>();
    ArrayDeque<UUID> queue = new ArrayDeque<>();
    queue.add(from);
    while (!queue.isEmpty()) {
      UUID curr = queue.removeFirst();
      if (curr.equals(to) && !curr.equals(from)) {
        return true;
      }
      if (!visited.add(curr)) continue;
      for (EdgeDefinition edge : outgoing.getOrDefault(curr, List.of())) {
        if (edge.getTargetNodeId().equals(to)) {
          return true;
        }
        queue.add(edge.getTargetNodeId());
      }
    }
    return false;
  }

  // --- STAGE 3 HELPERS ---
  private ExpressionSchema buildExpressionSchema(
      ValidationDefinition definition,
      Map<UUID, NodeTypeManifest> manifests,
      List<CompilerIssue> issues) {
    Map<String, TypeDescriptor> paths = new HashMap<>();
    Set<String> repeating = new HashSet<>();
    definition
        .variables()
        .forEach(variable -> paths.put("variables." + variable.getKey(), variable.getType()));
    for (WorkflowForm form : definition.forms()) {
      try {
        FormSchema schema = objectMapper.treeToValue(form.getSchemaJson(), FormSchema.class);
        schema
            .fields()
            .forEach(
                field -> {
                  paths.put("form." + field.key(), field.type());
                  paths.put("ticket." + field.key(), field.type());
                  paths.put("ticket.data." + field.key(), field.type());
                });
      } catch (JsonProcessingException | IllegalArgumentException exception) {
        issue(
            issues,
            "INVALID_FORM_SCHEMA",
            form,
            "/schema",
            "Form schema cannot be decoded",
            "Use the canonical FormSchema contract");
      }
    }
    for (NodeDefinition node : definition.nodes()) {
      NodeTypeManifest manifest = manifests.get(node.getId());
      if (manifest == null) {
        continue;
      }
      CanonicalSchema output = manifest.outputSchema();
      if (node.getOutputSchemaJson() != null) {
        try {
          output = objectMapper.treeToValue(node.getOutputSchemaJson(), CanonicalSchema.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
          issue(
              issues,
              "INVALID_OUTPUT_SCHEMA",
              node,
              "/outputSchema",
              "Output schema cannot be decoded",
              "Use CanonicalSchema");
        }
      } else if ("SYSTEM_ACTION".equals(node.getNodeType()) && connectorRegistry != null) {
        try {
          String cKey = node.getConfigJson().path("connectorKey").asText(null);
          String aKey = node.getConfigJson().path("actionKey").asText(null);
          int vNo = node.getConfigJson().path("actionVersion").asInt(0);
          if (cKey != null && aKey != null && vNo >= 1) {
            ConnectorActionVersion actionVer =
                connectorRegistry.requireActionVersion(cKey, aKey, vNo);
            if (actionVer.getOutputSchemaJson() != null
                && !actionVer.getOutputSchemaJson().isEmpty()) {
              output =
                  objectMapper.treeToValue(actionVer.getOutputSchemaJson(), CanonicalSchema.class);
            }
          }
        } catch (Exception ignored) {
        }
      }
      for (Map.Entry<String, TypeDescriptor> property : output.properties().entrySet()) {
        paths.put(
            "nodes." + node.getNodeKey() + ".output." + property.getKey(), property.getValue());
        paths.put(
            "nodes." + node.getNodeKey() + ".latest.output." + property.getKey(),
            property.getValue());
      }
      JsonNode config = node.getConfigJson();
      if (config.has("multiInstance") || config.path("repeating").asBoolean(false)) {
        repeating.add(node.getNodeKey());
      }
    }
    paths.put("actor.id", TypeDescriptor.required(CanonicalValueType.USER_ID));
    return new ExpressionSchema(paths, repeating);
  }

  private void validateForms(
      ValidationDefinition definition, ExpressionSchema schema, List<CompilerIssue> issues) {
    for (WorkflowForm form : definition.forms()) {
      try {
        FormSchema decoded = objectMapper.treeToValue(form.getSchemaJson(), FormSchema.class);
        formEngine
            .validateSchema(decoded, FormValidationPhase.PUBLISH, schema)
            .issues()
            .forEach(
                formIssue ->
                    issues.add(
                        new CompilerIssue(
                            formIssue.code(),
                            formIssue.severity()
                                    == com.fpt.workflow.form.engine.FormIssueSeverity.ERROR
                                ? ValidationSeverity.ERROR
                                : ValidationSeverity.WARNING,
                            "FORM",
                            form.getId(),
                            formIssue.fieldPath(),
                            formIssue.message(),
                            "Fix the canonical form definition",
                            null)));
      } catch (JsonProcessingException | IllegalArgumentException exception) {
        // INVALID_FORM_SCHEMA is emitted while building the shared expression schema.
      }
    }
  }

  private void validateExpressions(
      ValidationDefinition definition, ExpressionSchema schema, List<CompilerIssue> issues) {
    for (EdgeDefinition edge : definition.edges()) {
      if (edge.getConditionJson() != null) {
        validateExpression(edge.getConditionJson(), edge, "/condition", schema, issues);
      }
    }
    for (NodeDefinition node : definition.nodes()) {
      JsonNode expression = node.getConfigJson().get("expression");
      if (expression != null) {
        validateExpression(expression, node, "/config/expression", schema, issues);
      }
    }
  }

  private void validateExpression(
      JsonNode json,
      Object resource,
      String path,
      ExpressionSchema schema,
      List<CompilerIssue> issues) {
    try {
      Expression expression = objectMapper.treeToValue(json, Expression.class);
      var result = expressionEngine.validate(expression, ExpressionScope.RUNTIME, schema);
      result
          .issues()
          .forEach(
              expressionIssue ->
                  issues.add(
                      new CompilerIssue(
                          "INVALID_TYPED_REFERENCE",
                          ValidationSeverity.ERROR,
                          resourceType(resource),
                          resourceId(resource),
                          path + expressionIssue.path().substring(1),
                          expressionIssue.code() + ": " + expressionIssue.message(),
                          "Use a statically known compatible path",
                          null)));
      if (result.valid() && result.inferredType().type() != CanonicalValueType.BOOLEAN) {
        issues.add(
            new CompilerIssue(
                "CONDITION_NOT_BOOLEAN",
                ValidationSeverity.ERROR,
                resourceType(resource),
                resourceId(resource),
                path,
                "Routing conditions must infer BOOLEAN",
                "Use a comparison or boolean operator",
                null));
      }
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      issues.add(
          new CompilerIssue(
              "INVALID_EXPRESSION",
              ValidationSeverity.ERROR,
              resourceType(resource),
              resourceId(resource),
              path,
              "Expression is not a safe typed AST",
              "Use the allowlisted expression contract",
              null));
    }
  }

  // --- STAGE 18: WORKFLOW_CONTRACT (v2.4.1 §7.4/§34.10) ---
  // Validates the reusable typed input contract (inputs.*), declared display states, and
  // reusable Human Task FormVersion references on the exact pinned contract rows. Missing
  // repository support (e.g. legacy unit-instantiated compilers) skips DB-backed checks but
  // never skips the in-ValidationDefinition contract checks: unknown inputs.* still block publish.
  private void validateWorkflowContract(
      ValidationDefinition definition, ExpressionSchema schema, List<CompilerIssue> issues) {
    var inputsByKey = new LinkedHashMap<String, com.fpt.workflow.definition.domain.WorkflowInputDefinition>();
    for (var input : definition.inputs()) {
      if (!inputsByKey.containsKey(input.getInputKey())) {
        inputsByKey.put(input.getInputKey(), input);
      }
    }
    if (workflowInputRepository != null) {
      var persisted =
          workflowInputRepository.findAllByWorkflowVersionIdOrderByOrdinalAsc(
              definition.version().getId());
      for (var input : persisted) {
        inputsByKey.putIfAbsent(input.getInputKey(), input);
      }
    }
    var stateKeys = new java.util.HashSet<String>();
    for (var state : definition.states()) {
      stateKeys.add(state.getStateKey());
    }
    if (workflowStateRepository != null) {
      for (var state :
          workflowStateRepository.findAllByWorkflowVersionIdOrderByDisplayOrderAsc(
              definition.version().getId())) {
        stateKeys.add(state.getStateKey());
      }
    }

    for (NodeDefinition node : definition.nodes()) {
      JsonNode config = node.getConfigJson();
      if (config == null) continue;
      validateNodeInputsReference(node, config, schema, inputsByKey, issues);
      validateBusinessStateKey(node, config, stateKeys, defaultKeys(), issues);
      validateTaskFormVersion(node, config, issues);
    }
  }

  private void validateNodeInputsReference(
      NodeDefinition node,
      JsonNode config,
      ExpressionSchema schema,
      Map<String, com.fpt.workflow.definition.domain.WorkflowInputDefinition> inputsByKey,
      List<CompilerIssue> issues) {
    JsonNode bindings = config.path("inputBindings");
    if (!bindings.isArray()) {
      scanRawInputsReferences(
          node, config.toString(), schema, inputsByKey, "/config", issues);
      return;
    }
    for (int index = 0; index < bindings.size(); index++) {
      JsonNode binding = bindings.get(index);
      String fieldPath = "/config/inputBindings/" + index;
      JsonNode expressionJson = binding.path("expression");
      if (expressionJson.isMissingNode() || expressionJson.isNull()) continue;
      Expression expression;
      try {
        expression = objectMapper.treeToValue(expressionJson, Expression.class);
      } catch (Exception decodeFailure) {
        continue; // INVALID_EXPRESSION is reported by REFERENCE_PATH_TYPE/NODE_CONFIG.
      }
      // Existence + static-type check of every inputs.* reference, without a second engine.
      collectReferencePaths(expression)
          .forEach(
              path -> {
                if (!path.namespace().equals("inputs")) return;
                String full = path.value();
                if (full.equals("inputs")) {
                  issue(
                      issues,
                      "INPUTS_BARE_NAMESPACE",
                      node,
                      fieldPath + "/expression",
                      "inputs.* requires an input key: " + full,
                      "Reference a declared Workflow input, e.g. inputs.amount");
                  return;
                }
                String key = path.segments().size() > 1 ? path.segments().get(1) : "";
                var declared = inputsByKey.get(key);
                if (declared == null) {
                  issues.add(
                      new CompilerIssue(
                          "UNKNOWN_WORKFLOW_INPUT",
                          ValidationSeverity.ERROR,
                          "NODE",
                          node.getId(),
                          fieldPath + "/expression",
                          "Reference to undeclared Workflow input '" + full + "'",
                          "Declare the input on this WorkflowVersion or fix the reference",
                          null));
                  return;
                }
                JsonNode expectedType = binding.path("expectedType");
                if (expectedType.isObject()) {
                  try {
                    var targetType =
                        objectMapper.treeToValue(
                            expectedType,
                            com.fpt.workflow.shared.domain.value.TypeDescriptor.class);
                    if (!com.fpt.workflow.shared.domain.value.TypeCompatibility.isAssignable(
                        declared.getType(), targetType)) {
                      issues.add(
                          new CompilerIssue(
                              "WORKFLOW_INPUT_TYPE_MISMATCH",
                              ValidationSeverity.ERROR,
                              "NODE",
                              node.getId(),
                              fieldPath + "/expression",
                              "Input '"
                                  + key
                                  + "' has type "
                                  + declared.getType().displayName()
                                  + " which is not assignable to the consuming type",
                              "Fix the binding type or the input declaration",
                              null));
                    }
                    if (declared.isSensitive()) {
                      issues.add(
                          new CompilerIssue(
                              "WORKFLOW_INPUT_SENSITIVE_USE",
                              ValidationSeverity.WARNING,
                              "NODE",
                              node.getId(),
                              fieldPath + "/expression",
                              "Input '"
                                  + key
                                  + "' is marked sensitive; evidence is masked in audit trails",
                              "Confirm the consuming node is allowed to read this input",
                              null));
                    }
                  } catch (Exception ignored) {
                    // expectedType contract shape is enforced by NODE_CONFIG.
                  }
                }
              });
    }
  }

  private void scanRawInputsReferences(
      NodeDefinition node,
      String jsonText,
      ExpressionSchema schema,
      Map<String, com.fpt.workflow.definition.domain.WorkflowInputDefinition> inputsByKey,
      String fieldPath,
      List<CompilerIssue> issues) {
    Matcher matcher =
        Pattern.compile("inputs\\.([A-Za-z][A-Za-z0-9_]*)").matcher(jsonText);
    java.util.Set<String> seen = new java.util.HashSet<>();
    while (matcher.find()) {
      String key = matcher.group(1);
      if (!seen.add(key)) continue;
      if (!inputsByKey.containsKey(key)) {
        issues.add(
            new CompilerIssue(
                "UNKNOWN_WORKFLOW_INPUT",
                ValidationSeverity.ERROR,
                "NODE",
                node.getId(),
                fieldPath,
                "Reference to undeclared Workflow input 'inputs." + key + "'",
                "Declare the input on this WorkflowVersion or fix the reference",
                null));
      }
    }
  }

  private List<com.fpt.workflow.resolver.expression.ReferencePath> collectReferencePaths(
      Expression expression) {
    List<com.fpt.workflow.resolver.expression.ReferencePath> result = new ArrayList<>();
    if (expression instanceof com.fpt.workflow.resolver.expression.ReferenceExpression reference) {
      result.add(reference.path());
    } else if (expression
        instanceof com.fpt.workflow.resolver.expression.OperatorExpression operation) {
      for (Expression operand : operation.operands()) {
        result.addAll(collectReferencePaths(operand));
      }
    }
    return result;
  }

  private java.util.Set<String> defaultKeys() {
    return java.util.Set.of("SUBMITTED");
  }

  // Reusable Human Task FormVersion reference contract (distinct from create-ticket Category Form
  // and Runtime Requested Fields). Only an exact, Published FormVersion may be pinned.
  private void validateTaskFormVersion(
      NodeDefinition node, JsonNode config, List<CompilerIssue> issues) {
    if (!isHumanTaskNode(node)) {
      return;
    }
    JsonNode reference = config.path("taskFormVersionId");
    if (reference.isMissingNode() || reference.isNull()) {
      // formKey alone does not pin a reusable FormVersion; v2.4.1 requires an exact version id.
      if (config.path("formKey").isTextual() && !config.path("formKey").asText().isBlank()) {
        issues.add(
            new CompilerIssue(
                "TASK_FORM_VERSION_NOT_PINNED",
                ValidationSeverity.ERROR,
                "NODE",
                node.getId(),
                "/config/formKey",
                "Human Task references a form by key without pinning an exact FormVersion",
                "Set taskFormVersionId to the exact reusable FormVersion",
                null));
      }
      return;
    }
    if (!reference.isTextual() || reference.asText().isBlank()) {
      issue(
          issues,
          "TASK_FORM_VERSION_INVALID",
          node,
          "/config/taskFormVersionId",
          "taskFormVersionId must be a non-blank FormVersion id",
          "Select an exact published FormVersion");
      return;
    }
    UUID formVersionId;
    try {
      formVersionId = UUID.fromString(reference.asText().trim());
    } catch (IllegalArgumentException invalid) {
      issue(
          issues,
          "TASK_FORM_VERSION_INVALID",
          node,
          "/config/taskFormVersionId",
          "taskFormVersionId is not a valid UUID",
          "Select an exact published FormVersion");
      return;
    }
    if (formVersionRepository == null) {
      return; // No persistence in this compiler instance (legacy unit compile).
    }
    var formVersion = formVersionRepository.findById(formVersionId);
    if (formVersion.isEmpty()) {
      issues.add(
          new CompilerIssue(
              "TASK_FORM_VERSION_MISSING",
              ValidationSeverity.ERROR,
              "NODE",
              node.getId(),
              "/config/taskFormVersionId",
              "Pinned task FormVersion does not exist: " + formVersionId,
              "Publish the Form or select an existing FormVersion",
              null));
      return;
    }
    if (!"PUBLISHED".equals(formVersion.get().getStatus())) {
      issues.add(
          new CompilerIssue(
              "TASK_FORM_VERSION_NOT_PUBLISHED",
              ValidationSeverity.ERROR,
              "NODE",
              node.getId(),
              "/config/taskFormVersionId",
              "Pinned task FormVersion is not Published: " + formVersionId,
              "Pin an exact Published FormVersion; the id must not drift to a later version",
              null));
    }
  }

  private boolean isHumanTaskNode(NodeDefinition node) {
    String type = node.getNodeType();
    return "REVIEW".equalsIgnoreCase(type)
        || "APPROVAL".equalsIgnoreCase(type)
        || "TASK".equalsIgnoreCase(type);
  }

  private void validateBusinessStateKey(
      NodeDefinition node,
      JsonNode config,
      java.util.Set<String> declaredStates,
      java.util.Set<String> baselineStates,
      List<CompilerIssue> issues) {
    JsonNode stateNode = config.path("businessStateKey");
    if (stateNode.isMissingNode() || stateNode.isNull()) return;
    String stateKey = stateNode.asText("").trim();
    if (stateKey.isEmpty()) {
      issue(
          issues,
          "BUSINESS_STATE_KEY_EMPTY",
          node,
          "/config/businessStateKey",
          "businessStateKey must not be blank when declared",
          "Remove the field or name a declared Workflow state");
      return;
    }
    if (!declaredStates.contains(stateKey) && !baselineStates.contains(stateKey)) {
      issues.add(
          new CompilerIssue(
              "UNKNOWN_BUSINESS_STATE",
              ValidationSeverity.ERROR,
              "NODE",
              node.getId(),
              "/config/businessStateKey",
              "Node declares undeclared business state '" + stateKey + "'",
              "Declare the state on this WorkflowVersion",
              null));
    }
  }

  // --- STAGE 6: ROUTING ---
  private void validateRouting(
      Map<UUID, List<EdgeDefinition>> outgoing,
      Map<UUID, NodeDefinition> nodes,
      List<CompilerIssue> issues) {
    outgoing.forEach(
        (nodeId, edges) -> {
          NodeDefinition node = nodes.get(nodeId);
          boolean isAllOutgoing =
              node != null
                  && ("PARALLEL_SPLIT".equals(node.getNodeType())
                      || "ALL_OUTGOING"
                          .equals(node.getConfigJson().path("routingMode").asText(null)));
          if (isAllOutgoing) {
            Set<Integer> priorities = new HashSet<>();
            boolean duplicatePriority =
                edges.stream().anyMatch(edge -> !priorities.add(edge.getPriority()));
            if (duplicatePriority) {
              issue(
                  issues,
                  "ROUTING_NON_DETERMINISTIC",
                  node,
                  "/routes",
                  "Routes have duplicate priorities",
                  "Use unique priorities for parallel branch edges");
            }
            return;
          }
          Map<String, List<EdgeDefinition>> byPort = new HashMap<>();
          edges.forEach(
              edge ->
                  byPort
                      .computeIfAbsent(edge.getSourcePort(), ignored -> new ArrayList<>())
                      .add(edge));
          byPort.forEach(
              (port, routes) -> {
                long defaults = routes.stream().filter(EdgeDefinition::isDefaultTransition).count();
                long unconditional =
                    routes.stream()
                        .filter(
                            edge -> edge.getConditionJson() == null && !edge.isDefaultTransition())
                        .count();
                long conditional =
                    routes.stream().filter(edge -> edge.getConditionJson() != null).count();
                Set<Integer> priorities = new HashSet<>();
                boolean duplicatePriority =
                    routes.stream().anyMatch(edge -> !priorities.add(edge.getPriority()));
                if (defaults > 1
                    || unconditional > 1
                    || (unconditional > 0 && routes.size() > 1)
                    || duplicatePriority) {
                  issue(
                      issues,
                      "ROUTING_NON_DETERMINISTIC",
                      nodes.get(nodeId),
                      "/routes/" + port,
                      "Routes are not deterministically ordered/exclusive",
                      "Use unique priorities and at most one default");
                }
                if (conditional > 0 && defaults == 0) {
                  issue(
                      issues,
                      "ROUTING_DEFAULT_REQUIRED",
                      nodes.get(nodeId),
                      "/routes/" + port,
                      "Conditional exhaustiveness cannot be proven",
                      "Add one default edge");
                }
              });
        });
  }

  private Set<UUID> reachable(
      List<NodeDefinition> starts,
      Map<UUID, List<EdgeDefinition>> outgoing,
      Map<UUID, NodeDefinition> nodes) {
    Set<UUID> visited = new HashSet<>();
    ArrayDeque<UUID> queue = new ArrayDeque<>();
    starts.forEach(start -> queue.add(start.getId()));
    while (!queue.isEmpty()) {
      UUID current = queue.removeFirst();
      if (!visited.add(current)) continue;
      outgoing.getOrDefault(current, List.of()).stream()
          .map(EdgeDefinition::getTargetNodeId)
          .filter(nodes::containsKey)
          .forEach(queue::addLast);
    }
    return visited;
  }

  private void issue(
      List<CompilerIssue> issues,
      String code,
      Object resource,
      String path,
      String message,
      String suggestion) {
    issues.add(
        new CompilerIssue(
            code,
            ValidationSeverity.ERROR,
            resourceType(resource),
            resourceId(resource),
            path,
            message,
            suggestion,
            null));
  }

  private String resourceType(Object resource) {
    if (resource instanceof NodeDefinition) return "NODE";
    if (resource instanceof EdgeDefinition) return "EDGE";
    if (resource instanceof WorkflowForm) return "FORM";
    return "WORKFLOW_VERSION";
  }

  private UUID resourceId(Object resource) {
    if (resource instanceof NodeDefinition node) return node.getId();
    if (resource instanceof EdgeDefinition edge) return edge.getId();
    if (resource instanceof WorkflowForm form) return form.getId();
    return ((com.fpt.workflow.definition.domain.WorkflowVersion) resource).getId();
  }
}
