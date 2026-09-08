package com.fpt.workflow.form.engine;

import com.fpt.workflow.resolver.expression.Expression;
import java.util.Objects;
import java.util.Optional;

public record FieldVisibility(FieldVisibilityMode mode, Expression condition) {
  public FieldVisibility {
    mode = Objects.requireNonNull(mode, "mode");
  }

  public static FieldVisibility always() {
    return new FieldVisibility(FieldVisibilityMode.ALWAYS, null);
  }

  public static FieldVisibility conditional(Expression condition) {
    return new FieldVisibility(FieldVisibilityMode.CONDITIONAL, condition);
  }

  public Optional<Expression> conditionalExpression() {
    return Optional.ofNullable(condition);
  }
}
