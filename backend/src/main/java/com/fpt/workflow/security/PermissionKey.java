package com.fpt.workflow.security;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public record PermissionKey(String value) {

  private static final String AUTHORITY_PREFIX = "PERM_";
  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9_.:-]{0,127}");

  public PermissionKey {
    value = normalize(value);
    if (!KEY_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid permission key");
    }
  }

  public static PermissionKey of(String value) {
    return new PermissionKey(value);
  }

  public String authority() {
    return AUTHORITY_PREFIX + value;
  }

  public static Optional<PermissionKey> fromAuthority(String authority) {
    if (authority == null || !authority.startsWith(AUTHORITY_PREFIX)) {
      return Optional.empty();
    }
    try {
      return Optional.of(of(authority.substring(AUTHORITY_PREFIX.length())));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Permission key must not be blank");
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
