package com.fpt.workflow.definition.dependency;

import java.util.List;
import java.util.Objects;

public record DependencyReport(FieldChange change, List<FieldDependency> dependencies) {

  public DependencyReport {
    Objects.requireNonNull(change, "change");
    dependencies = List.copyOf(dependencies);
  }

  public boolean hasBreakingDependencies() {
    return dependencies.stream().anyMatch(item -> item.severity() == DependencySeverity.ERROR);
  }
}
