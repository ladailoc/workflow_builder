package com.fpt.workflow.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PayloadSanitizer {

  private static final Set<String> ALLOWED_KEYS =
      Set.of("connectorkey", "actionkey", "nodekey", "formkey", "handlerkey");

  private static final Set<String> SENSITIVE_TERMS =
      Set.of(
          "password",
          "secret",
          "token",
          "apikey",
          "api_key",
          "authorization",
          "credential",
          "privatekey",
          "accesstoken",
          "access_token");

  private PayloadSanitizer() {}

  public static JsonNode sanitize(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isObject()) {
      ObjectNode copy = JsonNodeFactory.instance.objectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String fieldName = entry.getKey();
        JsonNode child = entry.getValue();
        if (isSensitive(fieldName)) {
          copy.put(fieldName, "***REDACTED***");
        } else {
          copy.set(fieldName, sanitize(child));
        }
      }
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = JsonNodeFactory.instance.arrayNode();
      for (JsonNode item : node) {
        copy.add(sanitize(item));
      }
      return copy;
    }
    return node;
  }

  public static String sanitizeString(String jsonString, ObjectMapper objectMapper) {
    if (jsonString == null || jsonString.isBlank()) {
      return null;
    }
    try {
      JsonNode tree = objectMapper.readTree(jsonString);
      JsonNode sanitized = sanitize(tree);
      return sanitized != null ? sanitized.toString() : null;
    } catch (Exception ex) {
      return "{\"error\":\"failed to sanitize payload\"}";
    }
  }

  private static boolean isSensitive(String fieldName) {
    if (fieldName == null) return false;
    String lower = fieldName.toLowerCase(Locale.ROOT);
    if (ALLOWED_KEYS.contains(lower)) {
      return false;
    }
    for (String term : SENSITIVE_TERMS) {
      if (lower.contains(term)) {
        return true;
      }
    }
    return false;
  }
}
