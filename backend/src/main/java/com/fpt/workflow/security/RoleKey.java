package com.fpt.workflow.security;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public record RoleKey(String value) {

  private static final String AUTHORITY_PREFIX = "ROLE_";
  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  public static final RoleKey USER = new RoleKey("USER");
  public static final RoleKey WORKFLOW_OWNER = new RoleKey("WORKFLOW_OWNER");
  public static final RoleKey WORKFLOW_EDITOR = new RoleKey("WORKFLOW_EDITOR");
  public static final RoleKey OPERATOR = new RoleKey("OPERATOR");
  public static final RoleKey ADMIN = new RoleKey("ADMIN");

  public RoleKey {
    value = normalize(value);
    if (!KEY_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid role key");
    }
  }

  public static RoleKey of(String value) {
    return new RoleKey(value);
  }

  public String authority() {
    return AUTHORITY_PREFIX + value;
  }

  public static Optional<RoleKey> fromAuthority(String authority) {
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
      throw new IllegalArgumentException("Role key must not be blank");
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
