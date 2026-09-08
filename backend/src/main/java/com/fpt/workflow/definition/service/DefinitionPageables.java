package com.fpt.workflow.definition.service;

import com.fpt.workflow.shared.domain.page.PageRequest;
import java.util.Map;
import org.springframework.data.domain.Sort;

final class DefinitionPageables {

  private DefinitionPageables() {}

  static org.springframework.data.domain.PageRequest toSpring(
      PageRequest request, Map<String, String> allowedProperties) {
    Sort sort = Sort.unsorted();
    for (var order : request.sort()) {
      String entityProperty = allowedProperties.get(order.property());
      if (entityProperty == null) {
        throw new IllegalArgumentException("Unsupported sort property: " + order.property());
      }
      Sort.Order springOrder =
          order.direction() == com.fpt.workflow.shared.domain.page.SortDirection.ASC
              ? Sort.Order.asc(entityProperty)
              : Sort.Order.desc(entityProperty);
      sort = sort.and(Sort.by(springOrder));
    }
    return org.springframework.data.domain.PageRequest.of(request.page(), request.size(), sort);
  }
}
