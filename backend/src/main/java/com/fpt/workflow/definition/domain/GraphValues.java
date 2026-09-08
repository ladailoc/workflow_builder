package com.fpt.workflow.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Set;

final class GraphValues {

  private static final Set<String> ROUTING_DESTINATION_FIELDS =
      Set.of(
          "targetNodeId",
          "target_node_id",
          "destinationNodeId",
          "destination_node_id",
          "nextNodeId",
          "next_node_id");

  private GraphValues() {}

  static JsonNode nodeConfig(JsonNode value) {
    JsonNode config = object(value, "configJson");
    if (containsRoutingDestination(config)) {
      throw new IllegalArgumentException(
          "Node config must not duplicate an edge routing destination");
    }
    return config;
  }

  static JsonNode object(JsonNode value, String field) {
    Objects.requireNonNull(value, field);
    if (!value.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return value.deepCopy();
  }

  static JsonNode nullableObject(JsonNode value, String field) {
    return value == null ? null : object(value, field);
  }

  private static boolean containsRoutingDestination(JsonNode value) {
    if (value.isObject()) {
      for (var field : value.properties()) {
        if (ROUTING_DESTINATION_FIELDS.contains(field.getKey())
            || containsRoutingDestination(field.getValue())) {
          return true;
        }
      }
    } else if (value.isArray()) {
      for (JsonNode item : value) {
        if (containsRoutingDestination(item)) {
          return true;
        }
      }
    }
    return false;
  }
}
