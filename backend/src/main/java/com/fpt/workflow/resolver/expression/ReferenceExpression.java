package com.fpt.workflow.resolver.expression;

import java.util.Objects;

public record ReferenceExpression(ReferencePath path) implements Expression {
  public ReferenceExpression {
    path = Objects.requireNonNull(path, "path");
  }

  public ReferenceExpression(String path) {
    this(new ReferencePath(path));
  }
}
