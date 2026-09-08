package com.fpt.workflow.resolver.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.Objects;

public record LiteralExpression(JsonNode value, TypeDescriptor type) implements Expression {

  public LiteralExpression {
    value = Objects.requireNonNull(value, "value").deepCopy();
    type = Objects.requireNonNull(type, "type");
    CanonicalValueValidator.requireValid(type, value);
  }
}
