package com.fpt.workflow.resolver.expression;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A dot-separated, data-only reference. Method calls, indexing, and executable syntax are
 * forbidden.
 */
public record ReferencePath(String value) {

  private static final Pattern SAFE_PATH =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

  public ReferencePath {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Reference path must not be blank");
    }
    value = unwrap(value.trim());
    if (!SAFE_PATH.matcher(value).matches()) {
      throw new IllegalArgumentException("Unsafe or invalid reference path: " + value);
    }
  }

  public List<String> segments() {
    return List.of(value.split("\\."));
  }

  public String namespace() {
    return segments().getFirst();
  }

  private static String unwrap(String path) {
    if (path.startsWith("${") && path.endsWith("}")) {
      return path.substring(2, path.length() - 1);
    }
    return path;
  }
}
