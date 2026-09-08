package com.fpt.workflow.form.engine;

import java.util.regex.Pattern;

/** Conservative regex subset without groups, alternation, lookarounds, or backreferences. */
public record SafeRegex(String pattern) {

  private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9 _.,@:/+*?{}\\[\\]\\-^$\\\\]+");

  public SafeRegex {
    if (pattern == null || pattern.isBlank() || pattern.length() > 256) {
      throw new IllegalArgumentException("Safe regex must contain 1..256 characters");
    }
    if (!ALLOWED.matcher(pattern).matches()
        || pattern.contains("(")
        || pattern.contains(")")
        || pattern.contains("|")
        || pattern.matches(".*(?:\\*|\\+|\\?)(?:\\*|\\+|\\?|\\{).*")) {
      throw new IllegalArgumentException("Regex uses constructs outside the safe subset");
    }
    Pattern.compile(pattern);
  }

  public boolean matches(String value) {
    return Pattern.matches(pattern, value);
  }
}
