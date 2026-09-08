package com.fpt.workflow.operations.command;

import com.fpt.workflow.shared.domain.CommandId;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record CommandInvocation(
    String scopeType,
    UUID scopeId,
    CommandId commandId,
    String commandType,
    Long expectedVersion,
    String requestHash) {

  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9._-]{0,127}");

  public CommandInvocation {
    scopeType = key(scopeType, "scopeType");
    Objects.requireNonNull(scopeId, "scopeId");
    Objects.requireNonNull(commandId, "commandId");
    commandType = key(commandType, "commandType");
    if (expectedVersion != null && expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
    if (requestHash == null || requestHash.isBlank()) {
      throw new IllegalArgumentException("requestHash must not be blank");
    }
    requestHash = requestHash.trim();
  }

  private static String key(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!KEY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " has an invalid technical key");
    }
    return normalized;
  }
}
