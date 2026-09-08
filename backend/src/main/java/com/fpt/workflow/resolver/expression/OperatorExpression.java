package com.fpt.workflow.resolver.expression;

import java.util.List;
import java.util.Objects;

public record OperatorExpression(ExpressionOperator operator, List<Expression> operands)
    implements Expression {

  public OperatorExpression {
    operator = Objects.requireNonNull(operator, "operator");
    operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
  }

  public static OperatorExpression of(ExpressionOperator operator, Expression... operands) {
    return new OperatorExpression(operator, List.of(operands));
  }
}
