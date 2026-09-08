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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Ordered static compiler. It never creates runtime executions or evaluates routes. */
@Component
public class WorkflowValidationCompiler {

  private static final List<ValidationStage> PIPELINE = List.of(ValidationStage.values());

  private final NodeTypeRegistry nodeTypeRegistry;
  private final DynamicFormEngine formEngine;
  private final SafeExpressionEngine expressionEngine;
  private final CanonicalDefinitionJson canonicalJson;
  private final ObjectMapper objectMapper;
  private final ControlledCycleAnalyzer cycleAnalyzer;
  private final ConnectorRegistry connectorRegistry;
  private final ActorContextProvider actorContextProvider;

  @Autowired
  public WorkflowValidationCompiler(
      NodeTypeRegistry nodeTypeRegistry,
      DynamicFormEngine formEngine,
      SafeExpressionEngine expressionEngine,
      CanonicalDefinitionJson canonicalJson,
      ObjectMapper objectMapper,
      ControlledCycleAnalyzer cycleAnalyzer,
      @Autowired(required = false) ConnectorRegistry connectorRegistry,
      @Autowired(required = false) ActorContextProvider actorContextProvider) {
    this.nodeTypeRegistry = nodeTypeRegistry;
    this.formEngine = formEngine;
    this.expressionEngine = expressionEngine;
    this.canonicalJson = canonicalJson;
    this.objectMapper = objectMapper;
    this.cycleAnalyzer = cycleAnalyzer;
    this.connectorRegistry = connectorRegistry;
    this.actorContextProvider = actorContextProvider;
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
        null);
  }

  public ValidationCompilation compile(ValidationDefinition definition) {
    List<CompilerIssue> issues = new ArrayList<>();
    Map<UUID, NodeDefinition> nodesById = new LinkedHashMap<>();
    definition.nodes().forEach(node -> nodesById.put(node.getId(), node));
    Map<UUID, NodeTypeManifest> manifests = validateNodes(definition, issues);
    ExpressionSchema expressionSchema = buildExpressionSchema(definition, manifests, issues);
    validateForms(definition, expressionSchema, issues);
    validateExpressions(definition, expressionSchema, issues);
    validateGraph(definition, nodesById, manifests, issues);
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
    JsonNode snapshot = canonicalJson.compile(definition);
    return new ValidationCompilation(
        definition.version().getId(),
        definition.version().getRevision(),
        canonicalJson.checksum(snapshot),
        PIPELINE,
        issues);
  }

  private Map<UUID, NodeTypeManifest> validateNodes(
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
      validateSystemActionNode(node, issues);
    }
    return manifests;
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

  private void validateGraph(
      ValidationDefinition definition,
      Map<UUID, NodeDefinition> nodes,
      Map<UUID, NodeTypeManifest> manifests,
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

    Map<UUID, List<EdgeDefinition>> outgoing = new HashMap<>();
    Map<UUID, List<EdgeDefinition>> incoming = new HashMap<>();
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
      outgoing.computeIfAbsent(edge.getSourceNodeId(), ignored -> new ArrayList<>()).add(edge);
      incoming.computeIfAbsent(edge.getTargetNodeId(), ignored -> new ArrayList<>()).add(edge);
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
    validateRouting(outgoing, nodes, issues);
  }

  private void validateRouting(
      Map<UUID, List<EdgeDefinition>> outgoing,
      Map<UUID, NodeDefinition> nodes,
      List<CompilerIssue> issues) {
    outgoing.forEach(
        (nodeId, edges) -> {
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
