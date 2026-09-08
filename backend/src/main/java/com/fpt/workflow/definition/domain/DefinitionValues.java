package com.fpt.workflow.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.regex.Pattern;

final class DefinitionValues {

  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");

  private DefinitionValues() {}

  static String key(String value) {
    if (value == null || !KEY_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid key");
    }
    return value;
  }

  static String requiredText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
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
}
