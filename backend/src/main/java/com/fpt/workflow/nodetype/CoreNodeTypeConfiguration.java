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
  NodeTypeProvider startNodeTypeProvider() {
    return provider(
        NodeType.START,
        Set.of(NodeCapability.ENTRY, NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        EMPTY_SCHEMA,
        Set.of("STARTED"),
        EMPTY_SCHEMA,
        "control");
  }

  @Bean
  NodeTypeProvider endNodeTypeProvider() {
    CanonicalSchema config =
        CanonicalSchema.strict(
            Map.of("outcome", TypeDescriptor.nullable(CanonicalValueType.STRING)), Set.of());
    return provider(
        NodeType.END,
        Set.of(NodeCapability.TERMINAL, NodeCapability.OUTPUT),
        EMPTY_SCHEMA,
        EMPTY_SCHEMA,
        Set.of("COMPLETED"),
        config,
        "control");
  }

  @Bean
  NodeTypeProvider approvalNodeTypeProvider() {
    return humanTaskProvider(
        NodeType.APPROVAL, Set.of("APPROVED", "REJECTED", "REVISION_REQUESTED"));
  }

  @Bean
  NodeTypeProvider reviewNodeTypeProvider() {
    return humanTaskProvider(NodeType.REVIEW, Set.of("SUBMITTED", "RETURNED"));
  }

  @Bean
  NodeTypeProvider conditionNodeTypeProvider() {
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
        "routing");
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
        "human-task");
  }

  private static NodeTypeProvider provider(
      NodeType nodeType,
      Set<NodeCapability> capabilities,
      CanonicalSchema inputSchema,
      CanonicalSchema outputSchema,
      Set<String> outputPorts,
      CanonicalSchema configSchema,
      String category) {
    CanonicalSchema effectiveConfigSchema = withRuntimeConfiguration(configSchema);
    ObjectNode uiSchema = JsonNodeFactory.instance.objectNode();
    uiSchema.put("category", category);
    NodeHandler handler = new ContractOnlyNodeHandler(nodeType);
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
    return CanonicalSchema.strict(properties, nodeSpecific.requiredProperties());
  }
}
