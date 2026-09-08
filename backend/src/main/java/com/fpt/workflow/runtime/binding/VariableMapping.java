package com.fpt.workflow.runtime.binding;

import com.fpt.workflow.resolver.expression.Expression;
import java.util.Objects;
import java.util.regex.Pattern;

public record VariableMapping(String variableKey, Expression expression) {

  private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");

  public VariableMapping {
    if (variableKey == null || !KEY.matcher(variableKey).matches()) {
      throw new IllegalArgumentException("Invalid variableKey");
    }
    expression = Objects.requireNonNull(expression, "expression");
  }
}
