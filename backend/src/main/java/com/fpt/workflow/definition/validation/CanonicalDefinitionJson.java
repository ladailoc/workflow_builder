package com.fpt.workflow.definition.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.form.domain.WorkflowForm;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class CanonicalDefinitionJson {

  private final ObjectMapper objectMapper;

  public CanonicalDefinitionJson(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ObjectNode compile(ValidationDefinition definition) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("workflowVersionId", definition.version().getId().toString());
    root.put("definitionId", definition.version().getDefinitionId().toString());
    root.put("revision", definition.version().getRevision());
    ArrayNode nodes = root.putArray("nodes");
    definition.nodes().stream()
        .sorted(Comparator.comparing(NodeDefinition::getNodeKey))
        .forEach(node -> nodes.add(node(node)));
    ArrayNode edges = root.putArray("edges");
    definition.edges().stream()
        .sorted(Comparator.comparing(EdgeDefinition::getId))
        .forEach(edge -> edges.add(edge(edge)));
    ArrayNode forms = root.putArray("forms");
    definition.forms().stream()
        .sorted(Comparator.comparing(WorkflowForm::getFormKey))
        .forEach(form -> forms.add(form(form)));
    ArrayNode variables = root.putArray("variables");
    definition.variables().stream()
        .sorted(Comparator.comparing(WorkflowVariable::getKey))
        .forEach(variable -> variables.add(variable(variable)));
    return (ObjectNode) canonicalize(root);
  }

  public JsonNode canonicalize(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = objectMapper.createObjectNode();
      Map<String, JsonNode> sorted = new TreeMap<>();
      value.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
      sorted.forEach((key, child) -> result.set(key, canonicalize(child)));
      return result;
    }
    if (value.isArray()) {
      ArrayNode result = objectMapper.createArrayNode();
      value.forEach(child -> result.add(canonicalize(child)));
      return result;
    }
    return value.deepCopy();
  }

  public String checksum(JsonNode canonicalJson) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonicalJson.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }

  private ObjectNode node(NodeDefinition node) {
    ObjectNode json = objectMapper.createObjectNode();
    json.put("id", node.getId().toString());
    json.put("key", node.getNodeKey());
    json.put("type", node.getNodeType());
    json.put("name", node.getName());
    json.put("configSchemaVersion", node.getConfigSchemaVersion());
    json.set("config", node.getConfigJson());
    json.set("inputSchema", node.getInputSchemaJson());
    json.set("outputSchema", node.getOutputSchemaJson());
    return json;
  }

  private ObjectNode edge(EdgeDefinition edge) {
    ObjectNode json = objectMapper.createObjectNode();
    json.put("id", edge.getId().toString());
    json.put("sourceNodeId", edge.getSourceNodeId().toString());
    json.put("sourcePort", edge.getSourcePort());
    json.put("targetNodeId", edge.getTargetNodeId().toString());
    json.set("condition", edge.getConditionJson());
    json.put("priority", edge.getPriority());
    json.put("default", edge.isDefaultTransition());
    json.put("transitionType", edge.getTransitionType().name());
    json.set("config", edge.getConfigJson());
    return json;
  }

  private ObjectNode form(WorkflowForm form) {
    ObjectNode json = objectMapper.createObjectNode();
    json.put("id", form.getId().toString());
    json.put("key", form.getFormKey());
    json.put("type", form.getFormType().name());
    json.put("schemaChecksum", form.getSchemaChecksum());
    json.set("schema", form.getSchemaJson());
    return json;
  }

  private ObjectNode variable(WorkflowVariable variable) {
    ObjectNode json = objectMapper.createObjectNode();
    json.put("id", variable.getId().toString());
    json.put("key", variable.getKey());
    json.set("type", objectMapper.valueToTree(variable.getType()));
    json.put("scope", variable.getScope().name());
    json.set("default", variable.getDefaultJson());
    json.put("mutable", variable.isMutable());
    json.put("sensitive", variable.isSensitive());
    return json;
  }
}
