package com.fpt.workflow.resolver.expression;

import com.fasterxml.jackson.databind.JsonNode;

public interface ExpressionEngine {
  ExpressionValidationResult validate(
      Expression expression, ExpressionScope scope, ExpressionSchema schema);

  CompiledExpression compile(Expression expression, ExpressionScope scope, ExpressionSchema schema);

  JsonNode evaluate(CompiledExpression expression, JsonNode context, NullPolicy nullPolicy);
}
