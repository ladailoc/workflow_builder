package com.fpt.workflow.form.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.resolver.expression.ExpressionSchema;

public interface DynamicFormEngine {
  FormValidationResult validateSchema(
      FormSchema schema, FormValidationPhase phase, ExpressionSchema contextSchema);

  FormValidationResult validateSubmission(
      FormSchema schema,
      JsonNode submittedValues,
      JsonNode context,
      ExpressionSchema contextSchema);
}
