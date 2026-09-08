package com.fpt.workflow.form.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.regex.Pattern;

final class FormValues {

  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");

  private FormValues() {}

  static String key(String value) {
    if (value == null || !KEY_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid formKey");
    }
    return value;
  }

  static String checksum(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("schemaChecksum must not be blank");
    }
    return value.trim();
  }

  static JsonNode schema(JsonNode value) {
    Objects.requireNonNull(value, "schemaJson");
    if (!value.isObject()) {
      throw new IllegalArgumentException("schemaJson must be a JSON object");
    }
    return value.deepCopy();
  }
}
