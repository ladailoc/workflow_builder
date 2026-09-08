package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.value.CanonicalSchema;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record NodeTypeManifest(
    NodeType nodeType,
    int currentConfigSchemaVersion,
    Set<NodeCapability> supportedCapabilities,
    CanonicalSchema inputSchema,
    CanonicalSchema outputSchema,
    Set<String> outputPorts,
    CanonicalSchema configSchema,
    JsonNode uiSchema,
    NodeValidator validator,
    NodeHandler handler) {

  public NodeTypeManifest {
    nodeType = Objects.requireNonNull(nodeType, "nodeType");
    if (currentConfigSchemaVersion < 1) {
      throw new IllegalArgumentException("currentConfigSchemaVersion must be positive");
    }
    supportedCapabilities = Set.copyOf(supportedCapabilities);
    inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
    outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
    TreeSet<String> normalizedPorts = new TreeSet<>(outputPorts);
    if (normalizedPorts.stream().anyMatch(port -> port == null || port.isBlank())) {
      throw new IllegalArgumentException("outputPorts must not contain blank values");
    }
    outputPorts = Collections.unmodifiableSet(normalizedPorts);
    configSchema = Objects.requireNonNull(configSchema, "configSchema");
    if (configSchema.additionalProperties()) {
      throw new IllegalArgumentException("Executable node config schemas must be strict");
    }
    uiSchema = Objects.requireNonNull(uiSchema, "uiSchema").deepCopy();
    if (!uiSchema.isObject()) {
      throw new IllegalArgumentException("uiSchema must be an object");
    }
    validator = Objects.requireNonNull(validator, "validator");
    handler = Objects.requireNonNull(handler, "handler");
    if (handler.supports() != nodeType) {
      throw new IllegalArgumentException("Handler node type does not match manifest node type");
    }
  }

  public List<NodeValidationIssue> validate(String nodeKey, int schemaVersion, JsonNode config) {
    if (schemaVersion != currentConfigSchemaVersion) {
      return List.of(
          new NodeValidationIssue(
              "NODE.CONFIG_SCHEMA_VERSION_UNSUPPORTED",
              NodeValidationSeverity.ERROR,
              "$.configSchemaVersion",
              "Expected config schema version " + currentConfigSchemaVersion));
    }
    return validator.validate(new NodeValidationContext(nodeKey, schemaVersion, config));
  }
}
