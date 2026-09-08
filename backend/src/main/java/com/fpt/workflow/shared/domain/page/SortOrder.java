package com.fpt.workflow.shared.domain.page;

import java.util.Objects;
import java.util.regex.Pattern;

public record SortOrder(String property, SortDirection direction) {

  private static final Pattern PROPERTY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.]{0,63}");

  public SortOrder {
    if (property == null || !PROPERTY_PATTERN.matcher(property).matches()) {
      throw new IllegalArgumentException("Invalid sort property");
    }
    Objects.requireNonNull(direction, "direction");
  }

  public static SortOrder ascending(String property) {
    return new SortOrder(property, SortDirection.ASC);
  }

  public static SortOrder descending(String property) {
    return new SortOrder(property, SortDirection.DESC);
  }
}
