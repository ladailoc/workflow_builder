package com.fpt.workflow.definition.dependency;

import java.util.Objects;
import java.util.UUID;

public record FieldDependency(
    DependencyResourceType resourceType,
    UUID resourceId,
    String fieldPath,
    DependencyUsageType usageType,
    DependencySeverity severity,
    DependencyImpact impact) {

  public FieldDependency {
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(fieldPath, "fieldPath");
    Objects.requireNonNull(usageType, "usageType");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(impact, "impact");
  }
}
