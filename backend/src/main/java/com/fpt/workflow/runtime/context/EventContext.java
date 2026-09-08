package com.fpt.workflow.runtime.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.security.masking.SensitiveValueMasker;
import java.util.List;
import java.util.Objects;

/** Ephemeral runtime view. Only authoritative source records and snapshots are persisted. */
public final class EventContext {

  private final ObjectNode value;
  private final ExpressionSchema expressionSchema;
  private final List<SensitiveValueMetadata> sensitiveValues;
  private final SensitiveValueMasker masker;

  EventContext(
      ObjectNode value,
      ExpressionSchema expressionSchema,
      List<SensitiveValueMetadata> sensitiveValues,
      SensitiveValueMasker masker) {
    this.value = Objects.requireNonNull(value, "value").deepCopy();
    this.expressionSchema = Objects.requireNonNull(expressionSchema, "expressionSchema");
    this.sensitiveValues = List.copyOf(sensitiveValues);
    this.masker = Objects.requireNonNull(masker, "masker");
  }

  public ObjectNode value() {
    return value.deepCopy();
  }

  public ExpressionSchema expressionSchema() {
    return expressionSchema;
  }

  public List<SensitiveValueMetadata> sensitiveValues() {
    return sensitiveValues;
  }

  public ObjectNode maskedValue() {
    ObjectNode masked = value.deepCopy();
    sensitiveValues.forEach(metadata -> mask(masked, metadata.path()));
    return masked;
  }

  private void mask(ObjectNode root, String path) {
    String[] segments = path.split("\\.");
    JsonNode current = root;
    for (int index = 0; index < segments.length - 1; index++) {
      current = current.path(segments[index]);
      if (!current.isObject()) return;
    }
    if (current instanceof ObjectNode object && object.has(segments[segments.length - 1])) {
      JsonNode original = object.get(segments[segments.length - 1]);
      object.put(segments[segments.length - 1], masker.mask(original.toString()));
    }
  }
}
