package com.fpt.workflow.runtime.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.resolver.expression.Expression;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.Objects;
import java.util.regex.Pattern;

public record InputBinding(
    String target,
    Expression expression,
    TypeDescriptor expectedType,
    boolean required,
    MissingValueBehavior onMissing,
    JsonNode defaultValue) {

  private static final Pattern TARGET =
      Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*");

  public InputBinding {
    if (target == null || !TARGET.matcher(target).matches()) {
      throw new IllegalArgumentException("Invalid binding target");
    }
    expression = Objects.requireNonNull(expression, "expression");
    expectedType = Objects.requireNonNull(expectedType, "expectedType");
    onMissing = Objects.requireNonNull(onMissing, "onMissing");
    defaultValue = defaultValue == null ? null : defaultValue.deepCopy();
    if (onMissing == MissingValueBehavior.USE_DEFAULT && defaultValue == null) {
      throw new IllegalArgumentException("USE_DEFAULT requires defaultValue");
    }
    if (defaultValue != null) {
      CanonicalValueValidator.requireValid(expectedType, defaultValue);
    }
  }
}
