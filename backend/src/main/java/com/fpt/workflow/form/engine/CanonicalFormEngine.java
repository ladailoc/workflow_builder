package com.fpt.workflow.form.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.resolver.expression.CompiledExpression;
import com.fpt.workflow.resolver.expression.Expression;
import com.fpt.workflow.resolver.expression.ExpressionEngine;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.resolver.expression.ExpressionScope;
import com.fpt.workflow.resolver.expression.NullPolicy;
import com.fpt.workflow.shared.domain.value.CanonicalValidationIssue;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Canonical schema and user-submission validator for ticket and task forms. */
@Component
public final class CanonicalFormEngine implements DynamicFormEngine {

  private final ExpressionEngine expressionEngine;

  public CanonicalFormEngine(ExpressionEngine expressionEngine) {
    this.expressionEngine = Objects.requireNonNull(expressionEngine, "expressionEngine");
  }

  @Override
  public FormValidationResult validateSchema(
      FormSchema schema, FormValidationPhase phase, ExpressionSchema contextSchema) {
    Objects.requireNonNull(schema, "schema");
    Objects.requireNonNull(phase, "phase");
    ExpressionSchema effectiveSchema = withFormFields(schema, contextSchema);
    ExpressionScope expressionScope = expressionScope(schema);
    List<FormValidationIssue> issues = new ArrayList<>();
    checkDuplicates(schema, issues);
    for (int index = 0; index < schema.fields().size(); index++) {
      validateField(
          schema.fields().get(index), index, phase, expressionScope, effectiveSchema, issues);
    }
    return new FormValidationResult(issues);
  }

  @Override
  public FormValidationResult validateSubmission(
      FormSchema schema,
      JsonNode submittedValues,
      JsonNode context,
      ExpressionSchema contextSchema) {
    List<FormValidationIssue> issues =
        new ArrayList<>(
            validateSchema(schema, FormValidationPhase.PUBLISH, contextSchema).issues());
    if (submittedValues == null || !submittedValues.isObject()) {
      issue(
          issues,
          "FORM.OBJECT_REQUIRED",
          FormIssueSeverity.ERROR,
          "$",
          "Form values must be an object");
      return new FormValidationResult(issues);
    }
    if (context == null || !context.isObject()) {
      issue(
          issues,
          "FORM.CONTEXT_REQUIRED",
          FormIssueSeverity.ERROR,
          "$",
          "Form context must be an object");
      return new FormValidationResult(issues);
    }

    Map<String, FormFieldDefinition> declared =
        schema.fields().stream()
            .collect(
                Collectors.toMap(
                    FormFieldDefinition::key, Function.identity(), (left, right) -> left));
    submittedValues
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!declared.containsKey(key)) {
                issue(
                    issues,
                    "FORM.UNKNOWN_FIELD",
                    FormIssueSeverity.ERROR,
                    "$." + key,
                    "Ticket/task users may only submit fields declared by the workflow form schema");
              }
            });

    ObjectNode evaluationContext = ((ObjectNode) context.deepCopy());
    evaluationContext.set("form", submittedValues.deepCopy());
    ExpressionSchema effectiveSchema = withFormFields(schema, contextSchema);
    ExpressionScope expressionScope = expressionScope(schema);
    for (FormFieldDefinition field : schema.fields()) {
      validateSubmittedField(
          field, submittedValues, evaluationContext, expressionScope, effectiveSchema, issues);
    }
    return new FormValidationResult(issues);
  }

  private void validateField(
      FormFieldDefinition field,
      int index,
      FormValidationPhase phase,
      ExpressionScope expressionScope,
      ExpressionSchema expressionSchema,
      List<FormValidationIssue> issues) {
    String path = "$.fields[" + index + "]";
    if (field.label() == null || field.label().isBlank()) {
      issue(
          issues,
          "FORM.FIELD_LABEL_MISSING",
          phase == FormValidationPhase.PUBLISH
              ? FormIssueSeverity.ERROR
              : FormIssueSeverity.WARNING,
          path + ".label",
          "A field label is required before publish");
    }
    validateCondition(
        field.requirement().mode() == FieldRequirementMode.CONDITIONAL,
        field.requirement().condition(),
        path + ".requirement.condition",
        phase,
        expressionScope,
        expressionSchema,
        issues);
    validateCondition(
        field.visibility().mode() == FieldVisibilityMode.CONDITIONAL,
        field.visibility().condition(),
        path + ".visibility.condition",
        phase,
        expressionScope,
        expressionSchema,
        issues);
    validateCondition(
        field.editability().mode() == FieldEditabilityMode.CONDITIONAL,
        field.editability().condition(),
        path + ".editability.condition",
        phase,
        expressionScope,
        expressionSchema,
        issues);

    if (field.defaultValue() != null) {
      addCanonicalIssues(field.type(), field.defaultValue(), path + ".defaultValue", issues);
      if (field.sensitive()) {
        issue(
            issues,
            "FORM.SENSITIVE_DEFAULT_FORBIDDEN",
            FormIssueSeverity.ERROR,
            path + ".defaultValue",
            "Sensitive values must not be embedded in a versioned form schema");
      }
    }
    validateSemantics(field, path, issues);
    validateRules(field, path, phase, expressionScope, expressionSchema, issues);
    validateOptions(field, path, phase, issues);
  }

  private void validateCondition(
      boolean conditional,
      Expression condition,
      String path,
      FormValidationPhase phase,
      ExpressionScope expressionScope,
      ExpressionSchema schema,
      List<FormValidationIssue> issues) {
    if (!conditional) {
      if (condition != null) {
        issue(
            issues,
            "FORM.UNEXPECTED_CONDITION",
            FormIssueSeverity.ERROR,
            path,
            "Condition requires CONDITIONAL mode");
      }
      return;
    }
    if (condition == null) {
      issue(
          issues,
          "FORM.CONDITION_MISSING",
          phase == FormValidationPhase.PUBLISH
              ? FormIssueSeverity.ERROR
              : FormIssueSeverity.WARNING,
          path,
          "Conditional behavior requires a safe BOOLEAN expression before publish");
      return;
    }
    var result = expressionEngine.validate(condition, expressionScope, schema);
    result
        .issues()
        .forEach(
            expressionIssue ->
                issue(
                    issues,
                    expressionIssue.code(),
                    FormIssueSeverity.ERROR,
                    path,
                    expressionIssue.message()));
    if (result.type().isPresent()
        && result.type().orElseThrow().type() != CanonicalValueType.BOOLEAN) {
      issue(
          issues,
          "FORM.CONDITION_NOT_BOOLEAN",
          FormIssueSeverity.ERROR,
          path,
          "Condition must return BOOLEAN");
    }
  }

  private void validateSemantics(
      FormFieldDefinition field, String path, List<FormValidationIssue> issues) {
    FieldSemanticMetadata semantics = field.semantics();
    if (field.sensitive()
        && (semantics.filterable() || semantics.reportable() || semantics.searchable())) {
      issue(
          issues,
          "FORM.SENSITIVE_INDEXING_FORBIDDEN",
          FormIssueSeverity.ERROR,
          path + ".semantics",
          "Sensitive fields cannot be filterable, reportable, or searchable");
    }
    if ((semantics.participantCapable() || semantics.businessSubject())
        && !isReferenceType(field.type())) {
      issue(
          issues,
          "FORM.REFERENCE_SEMANTIC_TYPE_REQUIRED",
          FormIssueSeverity.ERROR,
          path + ".semantics",
          "Participant/business-subject metadata requires a canonical reference type");
    }
  }

  private void validateRules(
      FormFieldDefinition field,
      String path,
      FormValidationPhase phase,
      ExpressionScope expressionScope,
      ExpressionSchema expressionSchema,
      List<FormValidationIssue> issues) {
    FieldValidationRules rules = field.validation();
    if (rules.minimum() != null
        && rules.maximum() != null
        && rules.minimum().compareTo(rules.maximum()) > 0) {
      issue(
          issues,
          "FORM.INVALID_MIN_MAX",
          FormIssueSeverity.ERROR,
          path + ".validation",
          "minimum must not exceed maximum");
    }
    if (rules.minimumLength() != null && rules.minimumLength() < 0
        || rules.maximumLength() != null && rules.maximumLength() < 0
        || rules.minimumLength() != null
            && rules.maximumLength() != null
            && rules.minimumLength() > rules.maximumLength()) {
      issue(
          issues,
          "FORM.INVALID_LENGTH_RANGE",
          FormIssueSeverity.ERROR,
          path + ".validation",
          "Invalid length range");
    }
    if ((rules.minimum() != null || rules.maximum() != null) && !isNumeric(field.type())) {
      issue(
          issues,
          "FORM.NUMERIC_RULE_TYPE_MISMATCH",
          FormIssueSeverity.ERROR,
          path + ".validation",
          "Numeric rules require NUMBER, INTEGER, or MONEY");
    }
    if ((rules.minimumLength() != null || rules.maximumLength() != null || rules.regex() != null)
        && !supportsLength(field.type())) {
      issue(
          issues,
          "FORM.LENGTH_RULE_TYPE_MISMATCH",
          FormIssueSeverity.ERROR,
          path + ".validation",
          "Length/regex rules require STRING, ENUM, or ARRAY");
    }
    for (int index = 0; index < rules.safeRules().size(); index++) {
      validateCondition(
          true,
          rules.safeRules().get(index),
          path + ".validation.safeRules[" + index + "]",
          phase,
          expressionScope,
          expressionSchema,
          issues);
    }
  }

  private static void validateOptions(
      FormFieldDefinition field,
      String path,
      FormValidationPhase phase,
      List<FormValidationIssue> issues) {
    FieldOptions options = field.options();
    if (options == null) {
      return;
    }
    if (field.type().type() != CanonicalValueType.ENUM
        && field.type().type() != CanonicalValueType.STRING) {
      issue(
          issues,
          "FORM.OPTIONS_TYPE_MISMATCH",
          FormIssueSeverity.ERROR,
          path + ".options",
          "Options require ENUM or STRING");
    }
    if (options.source() == FieldOptionSource.STATIC) {
      if (options.dataSourceKey() != null || options.staticValues().isEmpty()) {
        issue(
            issues,
            "FORM.STATIC_OPTIONS_INCOMPLETE",
            phase == FormValidationPhase.PUBLISH
                ? FormIssueSeverity.ERROR
                : FormIssueSeverity.WARNING,
            path + ".options",
            "Static options require values and no dataSourceKey");
      }
    } else if (options.dataSourceKey() == null || !options.staticValues().isEmpty()) {
      issue(
          issues,
          "FORM.DATA_SOURCE_OPTIONS_INCOMPLETE",
          phase == FormValidationPhase.PUBLISH
              ? FormIssueSeverity.ERROR
              : FormIssueSeverity.WARNING,
          path + ".options",
          "Data-source options require a registered key and no static values");
    }
  }

  private void validateSubmittedField(
      FormFieldDefinition field,
      JsonNode submitted,
      ObjectNode context,
      ExpressionScope expressionScope,
      ExpressionSchema expressionSchema,
      List<FormValidationIssue> issues) {
    String path = "$." + field.key();
    boolean present = submitted.has(field.key()) && !submitted.get(field.key()).isNull();
    boolean visible =
        field.visibility().mode() == FieldVisibilityMode.ALWAYS
            || evaluateCondition(
                field.visibility().condition(),
                context,
                expressionScope,
                expressionSchema,
                path + ".visibility",
                issues);
    boolean editable =
        switch (field.editability().mode()) {
          case EDITABLE -> true;
          case READ_ONLY -> false;
          case CONDITIONAL ->
              evaluateCondition(
                  field.editability().condition(),
                  context,
                  expressionScope,
                  expressionSchema,
                  path + ".editability",
                  issues);
        };
    boolean required =
        switch (field.requirement().mode()) {
          case ALWAYS -> true;
          case NEVER -> false;
          case CONDITIONAL ->
              evaluateCondition(
                  field.requirement().condition(),
                  context,
                  expressionScope,
                  expressionSchema,
                  path + ".requirement",
                  issues);
        };
    if (present && !visible) {
      issue(
          issues,
          "FORM.FIELD_NOT_VISIBLE",
          FormIssueSeverity.ERROR,
          path,
          "A hidden field cannot be submitted");
    }
    if (present && !editable) {
      issue(
          issues,
          "FORM.FIELD_READ_ONLY",
          FormIssueSeverity.ERROR,
          path,
          "A read-only field cannot be submitted by a user");
    }
    JsonNode effectiveValue = present ? submitted.get(field.key()) : field.defaultValue();
    if (required && visible && effectiveValue == null) {
      issue(
          issues,
          "FORM.REQUIRED_FIELD_MISSING",
          FormIssueSeverity.ERROR,
          path,
          "Required field is missing");
      return;
    }
    if (effectiveValue == null) {
      return;
    }
    int issueCountBeforeTypeValidation = issues.size();
    addCanonicalIssues(field.type(), effectiveValue, path, issues);
    if (issues.size() == issueCountBeforeTypeValidation) {
      for (int index = 0; index < field.validation().safeRules().size(); index++) {
        if (!evaluateCondition(
            field.validation().safeRules().get(index),
            context,
            expressionScope,
            expressionSchema,
            path + ".safeRules[" + index + "]",
            issues)) {
          issue(
              issues,
              "FORM.SAFE_RULE_FAILED",
              FormIssueSeverity.ERROR,
              path,
              "Value does not satisfy a configured safe validation rule");
        }
      }
    }
    validateValueRules(field, effectiveValue, path, issues);
  }

  private boolean evaluateCondition(
      Expression condition,
      JsonNode context,
      ExpressionScope expressionScope,
      ExpressionSchema expressionSchema,
      String path,
      List<FormValidationIssue> issues) {
    if (condition == null) {
      return false;
    }
    try {
      CompiledExpression compiled =
          expressionEngine.compile(condition, expressionScope, expressionSchema);
      return expressionEngine.evaluate(compiled, context, NullPolicy.NULL_IS_FALSE).booleanValue();
    } catch (RuntimeException exception) {
      issue(
          issues,
          "FORM.CONDITION_EVALUATION_FAILED",
          FormIssueSeverity.ERROR,
          path,
          "Condition could not be evaluated against typed form data");
      return false;
    }
  }

  private static void validateValueRules(
      FormFieldDefinition field, JsonNode value, String path, List<FormValidationIssue> issues) {
    FieldValidationRules rules = field.validation();
    BigDecimal numeric = numericValue(field.type(), value);
    if (numeric != null && rules.minimum() != null && numeric.compareTo(rules.minimum()) < 0) {
      issue(
          issues,
          "FORM.VALUE_BELOW_MINIMUM",
          FormIssueSeverity.ERROR,
          path,
          "Value is below minimum");
    }
    if (numeric != null && rules.maximum() != null && numeric.compareTo(rules.maximum()) > 0) {
      issue(
          issues,
          "FORM.VALUE_ABOVE_MAXIMUM",
          FormIssueSeverity.ERROR,
          path,
          "Value is above maximum");
    }
    int length = value.isTextual() || value.isArray() ? value.size() : -1;
    if (value.isTextual()) {
      length = value.textValue().length();
    }
    if (length >= 0 && rules.minimumLength() != null && length < rules.minimumLength()) {
      issue(
          issues,
          "FORM.VALUE_TOO_SHORT",
          FormIssueSeverity.ERROR,
          path,
          "Value is shorter than minimumLength");
    }
    if (length >= 0 && rules.maximumLength() != null && length > rules.maximumLength()) {
      issue(
          issues,
          "FORM.VALUE_TOO_LONG",
          FormIssueSeverity.ERROR,
          path,
          "Value is longer than maximumLength");
    }
    if (rules.regex() != null && value.isTextual() && !rules.regex().matches(value.textValue())) {
      issue(
          issues,
          "FORM.REGEX_MISMATCH",
          FormIssueSeverity.ERROR,
          path,
          "Value does not match the safe regex");
    }
    if (field.options() != null
        && field.options().source() == FieldOptionSource.STATIC
        && field.options().staticValues().stream().noneMatch(value::equals)) {
      issue(
          issues,
          "FORM.VALUE_NOT_IN_OPTIONS",
          FormIssueSeverity.ERROR,
          path,
          "Value is not an allowed option");
    }
  }

  private static void addCanonicalIssues(
      TypeDescriptor type, JsonNode value, String path, List<FormValidationIssue> issues) {
    for (CanonicalValidationIssue canonical :
        CanonicalValueValidator.validate(type, value).issues()) {
      issue(issues, canonical.code(), FormIssueSeverity.ERROR, path, canonical.message());
    }
  }

  private static ExpressionSchema withFormFields(
      FormSchema schema, ExpressionSchema contextSchema) {
    Objects.requireNonNull(contextSchema, "contextSchema");
    Map<String, TypeDescriptor> paths = new java.util.HashMap<>(contextSchema.pathTypes());
    schema.fields().forEach(field -> paths.put("form." + field.key(), field.type()));
    return new ExpressionSchema(paths, contextSchema.repeatingNodeKeys());
  }

  private static ExpressionScope expressionScope(FormSchema schema) {
    return schema.formType() == com.fpt.workflow.form.domain.WorkflowFormType.TICKET_FORM
        ? ExpressionScope.TICKET_FORM
        : ExpressionScope.TASK_FORM;
  }

  private static void checkDuplicates(FormSchema schema, List<FormValidationIssue> issues) {
    Set<String> keys = new HashSet<>();
    Set<UUID> ids = new HashSet<>();
    for (int index = 0; index < schema.fields().size(); index++) {
      FormFieldDefinition field = schema.fields().get(index);
      if (!keys.add(field.key())) {
        issue(
            issues,
            "FORM.DUPLICATE_FIELD_KEY",
            FormIssueSeverity.ERROR,
            "$.fields[" + index + "].key",
            "Field key must be unique");
      }
      if (!ids.add(field.fieldId())) {
        issue(
            issues,
            "FORM.DUPLICATE_FIELD_ID",
            FormIssueSeverity.ERROR,
            "$.fields[" + index + "].fieldId",
            "Field id must be unique");
      }
    }
  }

  private static boolean isReferenceType(TypeDescriptor type) {
    TypeDescriptor effective = type.isCollection() ? type.itemType() : type;
    return switch (effective.type()) {
      case USER_ID, USER, DEPARTMENT_ID, GROUP_ID -> true;
      default -> false;
    };
  }

  private static boolean isNumeric(TypeDescriptor type) {
    return !type.isCollection()
        && Set.of(CanonicalValueType.NUMBER, CanonicalValueType.INTEGER, CanonicalValueType.MONEY)
            .contains(type.type());
  }

  private static boolean supportsLength(TypeDescriptor type) {
    return type.isCollection()
        || Set.of(CanonicalValueType.STRING, CanonicalValueType.ENUM).contains(type.type());
  }

  private static BigDecimal numericValue(TypeDescriptor type, JsonNode value) {
    if (value.isNumber()) {
      return value.decimalValue();
    }
    if (type.type() == CanonicalValueType.MONEY
        && value.isObject()
        && value.path("amount").isNumber()) {
      return value.path("amount").decimalValue();
    }
    return null;
  }

  private static void issue(
      List<FormValidationIssue> issues,
      String code,
      FormIssueSeverity severity,
      String path,
      String message) {
    issues.add(new FormValidationIssue(code, severity, path, message));
  }
}
