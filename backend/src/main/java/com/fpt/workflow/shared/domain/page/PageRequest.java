package com.fpt.workflow.shared.domain.page;

import java.util.List;

public record PageRequest(int page, int size, List<SortOrder> sort) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 200;

  public PageRequest {
    if (page < 0) {
      throw new IllegalArgumentException("Page index must not be negative");
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new IllegalArgumentException("Page size must be between 1 and " + MAX_SIZE);
    }
    sort = List.copyOf(sort);
  }

  public static PageRequest of(int page, int size) {
    return new PageRequest(page, size, List.of());
  }

  public static PageRequest firstPage() {
    return of(0, DEFAULT_SIZE);
  }
}
