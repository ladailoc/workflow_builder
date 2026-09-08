package com.fpt.workflow.runtime.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.Map;
import java.util.UUID;

/** Correlation scope used when resolving repeated node outputs. */
public record RuntimeScope(
    UUID cycleId,
    String pathToken,
    String itemToken,
    JsonNode item,
    Map<String, TypeDescriptor> itemTypes,
    JsonNode task,
    Map<String, TypeDescriptor> taskTypes) {

  public RuntimeScope {
    pathToken = optionalText(pathToken);
    itemToken = optionalText(itemToken);
    item = objectOrEmpty(item, "item");
    itemTypes = Map.copyOf(itemTypes == null ? Map.of() : itemTypes);
    task = objectOrEmpty(task, "task");
    taskTypes = Map.copyOf(taskTypes == null ? Map.of() : taskTypes);
  }

  public static RuntimeScope event() {
    return new RuntimeScope(null, null, null, null, Map.of(), null, Map.of());
  }

  public static RuntimeScope occurrence(UUID cycleId, String pathToken, String itemToken) {
    return new RuntimeScope(cycleId, pathToken, itemToken, null, Map.of(), null, Map.of());
  }

  private static JsonNode objectOrEmpty(JsonNode value, String field) {
    if (value == null) {
      return JsonNodeFactory.instance.objectNode();
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return value.deepCopy();
  }

  private static String optionalText(String value) {
    if (value == null) return null;
    if (value.isBlank()) throw new IllegalArgumentException("scope token must not be blank");
    return value.trim();
  }
}
