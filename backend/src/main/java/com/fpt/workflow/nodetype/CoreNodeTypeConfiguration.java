package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.shared.domain.value.CanonicalSchema;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Initial P0 node providers. Each manifest remains independent of workflow/business names. */
@Configuration(proxyBeanMethods = false)
public class CoreNodeTypeConfiguration {

  private static final CanonicalSchema EMPTY_SCHEMA = CanonicalSchema.strict(Map.of(), Set.of());

  @Bean
  public NodeTypeProvider startNodeTypeProvider() {
    return provider(
        NodeType.START,
        Set.of(NodeCapability.ENTRY, NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        EMPTY_SCHEMA,
        Set.of("STARTED"),
        EMPTY_SCHEMA,
        "control",
        new StartNodeHandler());
  }

  @Bean
  public NodeTypeProvider endNodeTypeProvider() {
    CanonicalSchema config =
        CanonicalSchema.strict(
            Map.of("outcome", TypeDescriptor.nullable(CanonicalValueType.STRING)), Set.of());
    return provider(
        NodeType.END,
        Set.of(NodeCapability.TERMINAL, NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        config,
        Set.of("COMPLETED"),
        config,
        "control",
        new EndNodeHandler());
  }

  @Bean
  public NodeTypeProvider approvalNodeTypeProvider() {
    return humanTaskProvider(
        NodeType.APPROVAL, Set.of("APPROVED", "REJECTED", "REVISION_REQUESTED"));
  }

  @Bean
  public NodeTypeProvider reviewNodeTypeProvider() {
    return humanTaskProvider(NodeType.REVIEW, Set.of("SUBMITTED", "RETURNED"));
  }

  @Bean
  public NodeTypeProvider conditionNodeTypeProvider() {
    CanonicalSchema config =
        CanonicalSchema.strict(
            Map.of("expression", TypeDescriptor.required(CanonicalValueType.OBJECT)),
            Set.of("expression"));
    return provider(
        NodeType.CONDITION,
        Set.of(NodeCapability.ROUTING, NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        EMPTY_SCHEMA,
        Set.of("TRUE", "FALSE", "ERROR"),
        config,
        "routing",
        new ConditionNodeHandler(
            new com.fasterxml.jackson.databind.ObjectMapper(),
            new com.fpt.workflow.resolver.expression.SafeExpressionEngine()));
  }

  @Bean
  public NodeTypeProvider joinNodeTypeProvider() {
    CanonicalSchema config =
        CanonicalSchema.strict(
            Map.of(
                "policy", TypeDescriptor.nullable(CanonicalValueType.STRING),
                "threshold", TypeDescriptor.nullable(CanonicalValueType.INTEGER)),
            Set.of());
    return provider(
        NodeType.JOIN,
        Set.of(NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        EMPTY_SCHEMA,
        Set.of("DEFAULT"),
        config,
        "control",
        new ContractOnlyNodeHandler(NodeType.JOIN));
  }

  @Bean
  public NodeTypeProvider parallelSplitNodeTypeProvider() {
    return provider(
        NodeType.PARALLEL_SPLIT,
        Set.of(NodeCapability.ROUTING, NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        EMPTY_SCHEMA,
        Set.of("SPLIT"),
        EMPTY_SCHEMA,
        "control",
        new ParallelSplitNodeHandler());
  }

  @Bean
  public NodeTypeProvider systemActionNodeTypeProvider() {
    CanonicalSchema config =
        CanonicalSchema.strict(
            Map.of(
                "connectorKey", TypeDescriptor.required(CanonicalValueType.STRING),
                "actionKey", TypeDescriptor.required(CanonicalValueType.STRING),
                "actionVersion", TypeDescriptor.required(CanonicalValueType.INTEGER),
                "credentialRef", TypeDescriptor.nullable(CanonicalValueType.STRING)),
            Set.of("connectorKey", "actionKey", "actionVersion"));
    return provider(
        NodeType.SYSTEM_ACTION,
        Set.of(NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        EMPTY_SCHEMA,
        Set.of("SUCCESS", "ERROR"),
        config,
        "integration",
        new SystemActionNodeHandler());
  }

  @Bean
  public NodeTypeProvider subWorkflowNodeTypeProvider() {
    CanonicalSchema config =
        CanonicalSchema.strict(
            Map.of(
                "childWorkflowDefinitionKey", TypeDescriptor.required(CanonicalValueType.STRING),
                "executionMode", TypeDescriptor.nullable(CanonicalValueType.STRING),
                "cancellationPolicy", TypeDescriptor.nullable(CanonicalValueType.STRING),
                "inputMappings", TypeDescriptor.nullable(CanonicalValueType.OBJECT),
                "outputMappings", TypeDescriptor.nullable(CanonicalValueType.OBJECT)),
            Set.of("childWorkflowDefinitionKey"));
    return provider(
        NodeType.SUB_WORKFLOW,
        Set.of(NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        CanonicalSchema.open(),
        Set.of("COMPLETED", "FAILED", "CANCELLED"),
        config,
        "sub-workflow",
        new SubWorkflowNodeHandler());
  }

  @Bean
  public NodeTypeProvider notificationNodeTypeProvider() {
    CanonicalSchema config =
        CanonicalSchema.strict(
            Map.of(
                "channel", TypeDescriptor.required(CanonicalValueType.STRING),
                "participant", TypeDescriptor.required(CanonicalValueType.OBJECT),
                "template", TypeDescriptor.required(CanonicalValueType.OBJECT),
                "maxAttempts", TypeDescriptor.nullable(CanonicalValueType.INTEGER),
                "allowAfterTerminal", TypeDescriptor.nullable(CanonicalValueType.BOOLEAN)),
            Set.of("channel", "participant", "template"));
    return provider(
        NodeType.NOTIFICATION,
        Set.of(NodeCapability.PARTICIPANT, NodeCapability.OUTPUT),
        CanonicalSchema.open(),
        EMPTY_SCHEMA,
        Set.of("QUEUED"),
        config,
        "notification",
        new NotificationNodeHandler());
  }

  private static NodeTypeProvider humanTaskProvider(NodeType nodeType, Set<String> outputPorts) {
    CanonicalSchema config =
        CanonicalSchema.strict(
            Map.of(
                "participant",
                TypeDescriptor.required(CanonicalValueType.OBJECT),
                "formKey",
                TypeDescriptor.nullable(CanonicalValueType.STRING),
                "allowedActions",
                TypeDescriptor.arrayOf(TypeDescriptor.required(CanonicalValueType.STRING))),
            Set.of("participant", "allowedActions"));
    return provider(
        nodeType,
        Set.of(
            NodeCapability.HUMAN_TASK,
            NodeCapability.PARTICIPANT,
            NodeCapability.FORM,
            NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        EMPTY_SCHEMA,
        outputPorts,
        config,
        "human-task",
        new ApprovalNodeHandler(nodeType));
  }

  private static NodeTypeProvider provider(
      NodeType nodeType,
      Set<NodeCapability> capabilities,
      CanonicalSchema inputSchema,
      CanonicalSchema outputSchema,
      Set<String> outputPorts,
      CanonicalSchema configSchema,
      String category,
      NodeHandler handler) {
    CanonicalSchema effectiveConfigSchema = withRuntimeConfiguration(configSchema);
    ObjectNode uiSchema = JsonNodeFactory.instance.objectNode();
    uiSchema.put("category", category);
    NodeTypeManifest manifest =
        new NodeTypeManifest(
            nodeType,
            1,
            capabilities,
            inputSchema,
            outputSchema,
            outputPorts,
            effectiveConfigSchema,
            uiSchema,
            new StrictNodeValidator(effectiveConfigSchema),
            handler);
    return () -> manifest;
  }

  private static CanonicalSchema withRuntimeConfiguration(CanonicalSchema nodeSpecific) {
    Map<String, TypeDescriptor> properties = new HashMap<>(nodeSpecific.properties());
    TypeDescriptor objectArray =
        TypeDescriptor.arrayOf(TypeDescriptor.required(CanonicalValueType.OBJECT));
    properties.put("inputBindings", objectArray);
    properties.put("variableMappings", objectArray);
    properties.put("routingMode", TypeDescriptor.nullable(CanonicalValueType.STRING));
    properties.put("multiInstance", TypeDescriptor.nullable(CanonicalValueType.OBJECT));
    properties.put("sla", TypeDescriptor.nullable(CanonicalValueType.OBJECT));
    properties.put("taskAggregation", TypeDescriptor.nullable(CanonicalValueType.OBJECT));
    properties.put("failure", TypeDescriptor.nullable(CanonicalValueType.OBJECT));
    return CanonicalSchema.strict(properties, nodeSpecific.requiredProperties());
  }
}
