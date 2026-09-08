package com.fpt.workflow.form.engine;

import com.fpt.workflow.resolver.expression.Expression;
import java.util.Objects;
import java.util.Optional;

public record FieldEditability(FieldEditabilityMode mode, Expression condition) {
  public FieldEditability {
    mode = Objects.requireNonNull(mode, "mode");
  }

  public static FieldEditability editable() {
    return new FieldEditability(FieldEditabilityMode.EDITABLE, null);
  }

  public static FieldEditability readOnly() {
    return new FieldEditability(FieldEditabilityMode.READ_ONLY, null);
  }

  public static FieldEditability conditional(Expression condition) {
    return new FieldEditability(FieldEditabilityMode.CONDITIONAL, condition);
  }

  public Optional<Expression> conditionalExpression() {
    return Optional.ofNullable(condition);
  }
}
