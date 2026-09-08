package com.fpt.workflow.ticket.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class TicketValues {

  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9._-]{0,127}");
  private static final Pattern SOURCE_FIELD_PATTERN =
      Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,255}");

  private TicketValues() {}

  static JsonNode dataObject(JsonNode value, String field) {
    Objects.requireNonNull(value, field);
    if (!value.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return value.deepCopy();
  }

  static String requiredText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  static String optionalText(String value) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("optional text must be null or non-blank");
    }
    return value.trim();
  }

  static String key(String value, String field) {
    String normalized = requiredText(value, field).toUpperCase(Locale.ROOT);
    if (!KEY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " has an invalid technical key");
    }
    return normalized;
  }

  static String sourceField(String value) {
    String normalized = optionalText(value);
    if (normalized != null && !SOURCE_FIELD_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException("sourceField has an invalid field path");
    }
    return normalized;
  }

  static Instant monotonicTime(Instant value, Instant lowerBound, String field) {
    Instant timestamp = Objects.requireNonNull(value, field);
    if (timestamp.isBefore(lowerBound)) {
      throw new IllegalArgumentException(field + " must not be before ticket creation");
    }
    return timestamp;
  }
}
