package com.fpt.workflow.definition.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PlatformSemanticDefaults {

  public static final int SCHEMA_VERSION = 1;
  private final ObjectMapper mapper;
  private final Environment environment;

  public PlatformSemanticDefaults(ObjectMapper mapper, Environment environment) {
    this.mapper = mapper;
    this.environment = environment;
  }

  public ObjectNode snapshot() {
    ObjectNode root = mapper.createObjectNode();
    root.put("schemaVersion", SCHEMA_VERSION);
    root.put("resolvedAt", "PUBLISH");
    ObjectNode routing = root.putObject("routing");
    routing.put(
        "routingNodeMode",
        property("workflow.semantic-defaults.routing.routing-node-mode", "EXCLUSIVE_CONDITIONAL"));
    routing.put(
        "ordinaryNodeMode",
        property("workflow.semantic-defaults.routing.ordinary-node-mode", "SINGLE_BY_PORT"));
    routing.put(
        "terminalNodeMode",
        property("workflow.semantic-defaults.routing.terminal-node-mode", "NONE"));
    ObjectNode multi = root.putObject("multiInstance");
    multi.put("itemVariable", "item");
    multi.put("executionMode", "PARALLEL");
    multi.put("completionPolicy", "ALL");
    multi.put("remainingItemPolicy", "CANCEL_REMAINING");
    ObjectNode aggregation = root.putObject("taskAggregation");
    aggregation.put("decisionPolicy", "ALL_APPROVE");
    aggregation.put("rejectBehavior", "FAIL_FAST");
    aggregation.put("remainingTaskBehavior", "CANCEL_REMAINING");
    aggregation.put("percentageThreshold", 50);
    aggregation.put("nOfMThreshold", 1);
    ObjectNode subWorkflow = root.putObject("subWorkflow");
    subWorkflow.put("versionResolution", "RESOLVE_AT_ACTIVATION");
    subWorkflow.put("executionMode", "WAIT_FOR_COMPLETION");
    subWorkflow.put("waitCancellationPolicy", "PROPAGATE");
    subWorkflow.put("fireAndContinueCancellationPolicy", "DETACH");
    subWorkflow.put("failureStrategy", "ROUTE_FAILED");
    ObjectNode systemAction = root.putObject("systemAction");
    systemAction.put("actionVersion", 1);
    systemAction.put("asyncCallback", false);
    systemAction.put("fallbackActionVersion", 1);
    ObjectNode sla = root.putObject("sla");
    sla.put("timeoutAction", "ESCALATE");
    sla.put("manualRecoveryTitle", "Manual SLA recovery");
    sla.put("manualRecoveryPriority", 90);
    sla.putObject("escalationResolver").put("type", "MANAGER_OF").put("depth", 1);
    return root;
  }

  public ObjectNode effectiveConfig(NodeDefinition node) {
    ObjectNode config =
        node.getConfigJson() != null && node.getConfigJson().isObject()
            ? (ObjectNode) node.getConfigJson()
            : mapper.createObjectNode();
    ObjectNode effective = config.deepCopy();
    String type = node.getNodeType().toUpperCase(Locale.ROOT);
    if (!effective.hasNonNull("routingMode")) {
      effective.put("routingMode", defaultRoutingMode(type));
    }
    if (effective.hasNonNull("multiInstance") && effective.path("multiInstance").isObject()) {
      ObjectNode multi = (ObjectNode) effective.path("multiInstance");
      if (!multi.hasNonNull("itemVariable")) {
        multi.put("itemVariable", "item");
      }
      if (!multi.hasNonNull("executionMode")) {
        multi.put("executionMode", "PARALLEL");
      }
      if (!multi.hasNonNull("completionPolicy")) {
        multi.put("completionPolicy", "ALL");
      }
      if (!multi.hasNonNull("remainingItemPolicy")) {
        multi.put("remainingItemPolicy", "CANCEL_REMAINING");
      }
    }
    materializeTaskAggregation(effective);
    if ("SUB_WORKFLOW".equals(type)) {
      materializeSubWorkflow(effective);
    }
    if ("SYSTEM_ACTION".equals(type)) {
      materializeSystemAction(effective);
    }
    return effective;
  }

  private void materializeTaskAggregation(ObjectNode effective) {
    JsonNode source =
        effective.hasNonNull("aggregation")
            ? effective.path("aggregation")
            : effective.hasNonNull("task") ? effective.path("task") : effective;
    if (!source.isObject()) {
      return;
    }
    ObjectNode target = (ObjectNode) source;
    if (!target.hasNonNull("decisionAggregationPolicy") && !target.hasNonNull("decisionPolicy")) {
      target.put("decisionPolicy", "ALL_APPROVE");
    }
    if (!target.hasNonNull("rejectBehavior")) {
      target.put("rejectBehavior", "FAIL_FAST");
    }
    if (!target.hasNonNull("remainingTaskBehavior")) {
      target.put("remainingTaskBehavior", "CANCEL_REMAINING");
    }
  }

  private void materializeSubWorkflow(ObjectNode effective) {
    if (!effective.hasNonNull("executionMode")) {
      effective.put("executionMode", "WAIT_FOR_COMPLETION");
    }
    if (!effective.hasNonNull("cancellationPolicy")) {
      String mode = effective.path("executionMode").asText("WAIT_FOR_COMPLETION");
      effective.put(
          "cancellationPolicy",
          "FIRE_AND_CONTINUE".equalsIgnoreCase(mode) ? "DETACH" : "PROPAGATE");
    }
    if (!effective.hasNonNull("failureStrategy")
        && !effective.path("failurePolicy").hasNonNull("strategy")
        && !effective.path("failure").hasNonNull("strategy")) {
      effective.put("failureStrategy", "ROUTE_FAILED");
    }
    effective.put("childVersionResolution", "RESOLVE_AT_ACTIVATION");
  }

  private void materializeSystemAction(ObjectNode effective) {
    if (!effective.hasNonNull("actionVersion")) {
      effective.put("actionVersion", 1);
    }
    if (!effective.hasNonNull("asyncCallback")) {
      effective.put("asyncCallback", false);
    }
  }

  private String defaultRoutingMode(String nodeType) {
    return switch (nodeType) {
      case "END" -> property("workflow.semantic-defaults.routing.terminal-node-mode", "NONE");
      case "START", "CONDITION", "PARALLEL_SPLIT" ->
          property(
              "workflow.semantic-defaults.routing.routing-node-mode", "EXCLUSIVE_CONDITIONAL");
      default ->
          property("workflow.semantic-defaults.routing.ordinary-node-mode", "SINGLE_BY_PORT");
    };
  }

  private String property(String key, String fallback) {
    return environment.getProperty(key, fallback).trim().toUpperCase(Locale.ROOT);
  }
}
