package com.fpt.workflow.resolver.expression;

import java.util.Set;

public enum ExpressionScope {
  TICKET_FORM(Set.of("form", "actor", "organization", "requestType")),
  TASK_FORM(Set.of("form", "ticket", "variables", "nodes", "item", "task", "actor")),
  RUNTIME(Set.of("ticket", "variables", "nodes", "item", "task", "actor"));

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
