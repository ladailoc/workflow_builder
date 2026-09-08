package com.fpt.workflow.runtime.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class RuntimeValues {

  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9._-]{0,127}");

  private RuntimeValues() {}

  static String key(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!KEY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " has an invalid technical key");
    }
    return normalized;
  }

  static String requiredText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  static String optionalText(String value, String field) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must be null or non-blank");
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

  static Instant notBefore(Instant value, Instant lowerBound, String field) {
    Instant timestamp = Objects.requireNonNull(value, field);
    if (timestamp.isBefore(lowerBound)) {
      throw new IllegalArgumentException(field + " must not be before creation");
    }
    return timestamp;
  }
}
