package com.fpt.workflow.form.engine;

import com.fpt.workflow.resolver.expression.Expression;
import java.util.Objects;
import java.util.Optional;

public record FieldRequirement(FieldRequirementMode mode, Expression condition) {
  public FieldRequirement {
    mode = Objects.requireNonNull(mode, "mode");
  }

  public static FieldRequirement always() {
    return new FieldRequirement(FieldRequirementMode.ALWAYS, null);
  }

  public static FieldRequirement never() {
    return new FieldRequirement(FieldRequirementMode.NEVER, null);
  }

  public static FieldRequirement conditional(Expression condition) {
    return new FieldRequirement(FieldRequirementMode.CONDITIONAL, condition);
  }

  public Optional<Expression> conditionalExpression() {
    return Optional.ofNullable(condition);
  }
}
