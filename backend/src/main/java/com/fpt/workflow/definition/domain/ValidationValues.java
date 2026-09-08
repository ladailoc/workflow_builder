package com.fpt.workflow.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;

final class ValidationValues {

  private static final Pattern RULE_CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9._-]{0,127}");

  private ValidationValues() {}

  static String requiredText(String value, String field) {
    return DefinitionValues.requiredText(value, field);
  }

  static String ruleCode(String value) {
    if (value == null || !RULE_CODE_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid ruleCode");
    }
    return value;
  }

  static String optionalText(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  static JsonNode metadata(JsonNode value) {
    return DefinitionValues.object(value, "metadataJson");
  }
}
