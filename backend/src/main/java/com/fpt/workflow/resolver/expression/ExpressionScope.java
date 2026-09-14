package com.fpt.workflow.resolver.expression;

import java.util.Set;

public enum ExpressionScope {
  TICKET_FORM(Set.of("form", "actor", "organization", "requestType", "category")),
  // v2.4.1: task forms may read mapped Workflow inputs and the original submission namespaces;
  // Form field values still only enter runtime through explicit CategoryMapping.
  TASK_FORM(
      Set.of(
          "form",
          "ticket",
          "variables",
          "nodes",
          "item",
          "task",
          "actor",
          "inputs",
          "category",
          "formSubmission")),
  // §7.4 runtime scope: inputs.*, ticket.*, variables.*, nodes.*, item.*, task.*, actor.*,
  // organization.* plus the v2.4.1 category/formSubmission read namespaces.
  RUNTIME(
      Set.of(
          "ticket",
          "variables",
          "nodes",
          "item",
          "task",
          "actor",
          "inputs",
          "category",
          "formSubmission",
          "organization"));

  private final Set<String> namespaces;

  ExpressionScope(Set<String> namespaces) {
    this.namespaces = Set.copyOf(namespaces);
  }

  public boolean allows(String namespace) {
    return namespaces.contains(namespace);
  }

  public Set<String> namespaces() {
    return namespaces;
  }
}
