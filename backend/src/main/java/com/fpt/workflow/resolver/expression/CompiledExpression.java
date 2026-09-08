package com.fpt.workflow.resolver.expression;

import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.Objects;

public record CompiledExpression(
    Expression expression,
    ExpressionScope scope,
    ExpressionSchema schema,
    TypeDescriptor resultType) {

  public CompiledExpression {
    expression = Objects.requireNonNull(expression, "expression");
    scope = Objects.requireNonNull(scope, "scope");
    schema = Objects.requireNonNull(schema, "schema");
    resultType = Objects.requireNonNull(resultType, "resultType");
  }
}
