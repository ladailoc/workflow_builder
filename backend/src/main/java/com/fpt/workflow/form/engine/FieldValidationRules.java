package com.fpt.workflow.form.engine;

import com.fpt.workflow.resolver.expression.Expression;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record FieldValidationRules(
    BigDecimal minimum,
    BigDecimal maximum,
    Integer minimumLength,
    Integer maximumLength,
    SafeRegex regex,
    List<Expression> safeRules) {

  public FieldValidationRules {
    safeRules = List.copyOf(Objects.requireNonNull(safeRules, "safeRules"));
  }

  public static FieldValidationRules none() {
    return new FieldValidationRules(null, null, null, null, null, List.of());
  }
}
