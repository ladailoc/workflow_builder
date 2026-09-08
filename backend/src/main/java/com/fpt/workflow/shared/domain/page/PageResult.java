package com.fpt.workflow.shared.domain.page;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long totalElements) {

  public PageResult {
    items = List.copyOf(items);
    if (page < 0) {
      throw new IllegalArgumentException("Page index must not be negative");
    }
    if (size < 1) {
      throw new IllegalArgumentException("Page size must be positive");
    }
    if (items.size() > size) {
      throw new IllegalArgumentException("Page contains more items than its declared size");
    }
    if (totalElements < 0) {
      throw new IllegalArgumentException("Total elements must not be negative");
    }
  }

  public long totalPages() {
    return totalElements == 0 ? 0 : Math.floorDiv(totalElements - 1, size) + 1;
  }

  public boolean hasNext() {
    return (long) page + 1 < totalPages();
  }
}
