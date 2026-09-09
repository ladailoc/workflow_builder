package com.fpt.workflow.operations.observability;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility for sanitizing and masking sensitive data before it is written to logs, MDC, or
 * monitoring signals. Ensures tokens, passwords, and private credentials are never leaked in log
 * streams.
 */
public final class SensitiveDataMasker {

  public static final String REDACTED = "[REDACTED]";

  private static final Set<String> SENSITIVE_KEY_FRAGMENTS =
      Set.of(
          "password",
          "secret",
          "token",
          "credential",
          "authorization",
          "apikey",
          "api_key",
          "privatekey",
          "private_key");

  private static final Pattern BEARER_PATTERN =
      Pattern.compile("Bearer\\s+[A-Za-z0-9\\-_.~+/]+=*", Pattern.CASE_INSENSITIVE);

  private static final Pattern SENSITIVE_JSON_FIELD_PATTERN =
      Pattern.compile(
          "\"([^\"]*(?:password|secret|token|credential|api_key|private_key)[^\"]*)\"\\s*:\\s*\"[^\"]*\"",
          Pattern.CASE_INSENSITIVE);

  private SensitiveDataMasker() {}

  /** Checks whether a field name, header, or attribute key represents sensitive data. */
  public static boolean isSensitiveKey(String key) {
    if (key == null) {
      return false;
    }
    String lower = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    for (String fragment : SENSITIVE_KEY_FRAGMENTS) {
      if (lower.contains(fragment.replace("_", ""))) {
        return true;
      }
    }
    return false;
  }

  /** Masks a value if the key indicates it is sensitive; otherwise returns the original value. */
  public static String maskIfSensitive(String key, String value) {
    if (value == null) {
      return null;
    }
    if (isSensitiveKey(key)) {
      return REDACTED;
    }
    return maskString(value);
  }

  /**
   * Masks known patterns (e.g. Bearer tokens or JSON sensitive fields) within a free-form string.
   */
  public static String maskString(String input) {
    if (input == null || input.isBlank()) {
      return input;
    }
    String masked = BEARER_PATTERN.matcher(input).replaceAll("Bearer " + REDACTED);
    return SENSITIVE_JSON_FIELD_PATTERN.matcher(masked).replaceAll("\"$1\":\"" + REDACTED + "\"");
  }
}
