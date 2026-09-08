package com.fpt.workflow.nodetype;

import com.fpt.workflow.shared.domain.value.CanonicalSchema;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import java.util.List;
import java.util.Objects;

/** Schema-first validator which turns every unknown executable property into a blocking error. */
public final class StrictNodeValidator implements NodeValidator {

  private final CanonicalSchema configSchema;

  public StrictNodeValidator(CanonicalSchema configSchema) {
    this.configSchema = Objects.requireNonNull(configSchema, "configSchema");
    if (configSchema.additionalProperties()) {
      throw new IllegalArgumentException("Executable node config schemas must be strict");
    }
  }

  @Override
  public List<NodeValidationIssue> validate(NodeValidationContext context) {
    return CanonicalValueValidator.validate(configSchema, context.config()).issues().stream()
        .map(
            issue ->
                new NodeValidationIssue(
                    issue.code(), NodeValidationSeverity.ERROR, issue.path(), issue.message()))
        .toList();
  }
}
