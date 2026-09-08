package com.fpt.workflow.resolver.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeCompatibility;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Deterministic evaluator for the closed expression AST. */
@Component
public final class SafeExpressionEngine implements ExpressionEngine {

  private static final TypeDescriptor BOOLEAN = TypeDescriptor.required(CanonicalValueType.BOOLEAN);

  @Override
  public ExpressionValidationResult validate(
      Expression expression, ExpressionScope scope, ExpressionSchema schema) {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(schema, "schema");
    List<ExpressionValidationIssue> issues = new ArrayList<>();
    TypeDescriptor inferred = infer(expression, scope, schema, "$", issues);
    return new ExpressionValidationResult(issues, inferred);
  }

  @Override
  public CompiledExpression compile(
      Expression expression, ExpressionScope scope, ExpressionSchema schema) {
    ExpressionValidationResult result = validate(expression, scope, schema);
    result.requireValid();
    return new CompiledExpression(expression, scope, schema, result.inferredType());
  }

  @Override
  public JsonNode evaluate(CompiledExpression expression, JsonNode context, NullPolicy nullPolicy) {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(nullPolicy, "nullPolicy");
    return evaluateNode(expression.expression(), context, nullPolicy, expression.schema());
  }

  private static TypeDescriptor infer(
      Expression expression,
      ExpressionScope scope,
      ExpressionSchema schema,
      String astPath,
      List<ExpressionValidationIssue> issues) {
    if (expression instanceof LiteralExpression literal) {
      return literal.type();
    }
    if (expression instanceof ReferenceExpression reference) {
      return inferReference(reference.path(), scope, schema, astPath, issues);
    }
    OperatorExpression operation = (OperatorExpression) expression;
    List<TypeDescriptor> operands = new ArrayList<>();
    for (int index = 0; index < operation.operands().size(); index++) {
      operands.add(
          infer(
              operation.operands().get(index),
              scope,
              schema,
              astPath + ".operands[" + index + "]",
              issues));
    }
    validateOperation(operation.operator(), operands, astPath, issues);
    return BOOLEAN;
  }

  private static TypeDescriptor inferReference(
      ReferencePath path,
      ExpressionScope scope,
      ExpressionSchema schema,
      String astPath,
      List<ExpressionValidationIssue> issues) {
    if (!scope.allows(path.namespace())) {
      issue(
          issues,
          "EXPRESSION.NAMESPACE_NOT_ALLOWED",
          astPath,
          "Namespace '" + path.namespace() + "' is not allowed in " + scope);
      return null;
    }
    if (schema.isAmbiguousRepeatedNodeReference(path)) {
      issue(
          issues,
          "EXPRESSION.REPEATED_NODE_REFERENCE_AMBIGUOUS",
          astPath,
          "Repeated node output requires latest, executions, items, or cycles addressing");
      return null;
    }
    return schema
        .typeOf(path)
        .orElseGet(
            () -> {
              issue(
                  issues,
                  "EXPRESSION.UNKNOWN_REFERENCE_PATH",
                  astPath,
                  "Reference path is not present in the static schema: " + path.value());
              return null;
            });
  }

  private static void validateOperation(
      ExpressionOperator operator,
      List<TypeDescriptor> operands,
      String path,
      List<ExpressionValidationIssue> issues) {
    int requiredArity =
        switch (operator) {
          case IS_NULL, NOT -> 1;
          case EQ, NE, GT, GTE, LT, LTE, IN, CONTAINS -> 2;
          case AND, OR -> -1;
        };
    if ((requiredArity >= 0 && operands.size() != requiredArity)
        || (requiredArity < 0 && operands.size() < 2)) {
      issue(issues, "EXPRESSION.INVALID_ARITY", path, "Invalid operand count for " + operator);
      return;
    }
    if (operands.stream().anyMatch(Objects::isNull)) {
      return;
    }
    switch (operator) {
      case AND, OR, NOT -> operands.forEach(type -> requireBoolean(type, operator, path, issues));
      case EQ, NE -> requireCompatible(operands.get(0), operands.get(1), operator, path, issues);
      case GT, GTE, LT, LTE -> {
        requireCompatible(operands.get(0), operands.get(1), operator, path, issues);
        if (!isOrdered(operands.get(0))) {
          issue(
              issues,
              "EXPRESSION.TYPE_NOT_ORDERED",
              path,
              operator + " does not support " + operands.get(0).displayName());
        }
      }
      case IN -> requireCollectionMembership(operands.get(0), operands.get(1), path, issues);
      case CONTAINS -> requireContains(operands.get(0), operands.get(1), path, issues);
      case IS_NULL -> {
        // All canonical types support an explicit null check.
      }
    }
  }

  private static void requireBoolean(
      TypeDescriptor type,
      ExpressionOperator operator,
      String path,
      List<ExpressionValidationIssue> issues) {
    if (type.type() != CanonicalValueType.BOOLEAN || type.isCollection()) {
      issue(issues, "EXPRESSION.BOOLEAN_REQUIRED", path, operator + " requires BOOLEAN operands");
    }
  }

  private static void requireCompatible(
      TypeDescriptor left,
      TypeDescriptor right,
      ExpressionOperator operator,
      String path,
      List<ExpressionValidationIssue> issues) {
    if (!TypeCompatibility.isAssignable(left, right)
        && !TypeCompatibility.isAssignable(right, left)) {
      issue(
          issues,
          "EXPRESSION.INCOMPATIBLE_TYPES",
          path,
          operator + " cannot compare " + left.displayName() + " and " + right.displayName());
    }
  }

  private static void requireCollectionMembership(
      TypeDescriptor item,
      TypeDescriptor collection,
      String path,
      List<ExpressionValidationIssue> issues) {
    if (!collection.isCollection()
        || (!TypeCompatibility.isAssignable(item, collection.itemType())
            && !TypeCompatibility.isAssignable(collection.itemType(), item))) {
      issue(
          issues,
          "EXPRESSION.IN_REQUIRES_COMPATIBLE_ARRAY",
          path,
          "IN requires a compatible ARRAY as its right operand");
    }
  }

  private static void requireContains(
      TypeDescriptor container,
      TypeDescriptor item,
      String path,
      List<ExpressionValidationIssue> issues) {
    boolean stringContains =
        container.type() == CanonicalValueType.STRING
            && item.type() == CanonicalValueType.STRING
            && !container.isCollection()
            && !item.isCollection();
    boolean collectionContains =
        container.isCollection()
            && (TypeCompatibility.isAssignable(item, container.itemType())
                || TypeCompatibility.isAssignable(container.itemType(), item));
    if (!stringContains && !collectionContains) {
      issue(
          issues,
          "EXPRESSION.CONTAINS_TYPE_MISMATCH",
          path,
          "CONTAINS requires STRING/STRING or ARRAY/compatible-item operands");
    }
  }

  private static boolean isOrdered(TypeDescriptor type) {
    return !type.isCollection()
        && switch (type.type()) {
          case NUMBER, INTEGER, STRING, ENUM, DATE, DATETIME, DURATION, MONEY -> true;
          default -> false;
        };
  }

  private static JsonNode evaluateNode(
      Expression expression,
      JsonNode context,
      NullPolicy nullPolicy,
      ExpressionSchema expressionSchema) {
    if (expression instanceof LiteralExpression literal) {
      return literal.value().deepCopy();
    }
    if (expression instanceof ReferenceExpression reference) {
      return resolve(context, reference.path());
    }
    OperatorExpression operation = (OperatorExpression) expression;
    List<JsonNode> values =
        operation.operands().stream()
            .map(operand -> evaluateNode(operand, context, nullPolicy, expressionSchema))
            .toList();
    List<TypeDescriptor> operandTypes =
        operation.operands().stream()
            .map(operand -> compiledTypeOf(operand, expressionSchema))
            .toList();
    return BooleanNode.valueOf(
        evaluateOperator(operation.operator(), values, operandTypes, nullPolicy));
  }

  private static boolean evaluateOperator(
      ExpressionOperator operator,
      List<JsonNode> values,
      List<TypeDescriptor> operandTypes,
      NullPolicy nullPolicy) {
    if (operator == ExpressionOperator.IS_NULL) {
      return isNullish(values.getFirst());
    }
    if (values.stream().anyMatch(SafeExpressionEngine::isNullish)) {
      if (nullPolicy == NullPolicy.ERROR) {
        throw new IllegalStateException("Null or missing expression operand");
      }
      return false;
    }
    return switch (operator) {
      case EQ -> values.get(0).equals(values.get(1));
      case NE -> !values.get(0).equals(values.get(1));
      case GT -> compare(values.get(0), values.get(1), operandTypes.get(0)) > 0;
      case GTE -> compare(values.get(0), values.get(1), operandTypes.get(0)) >= 0;
      case LT -> compare(values.get(0), values.get(1), operandTypes.get(0)) < 0;
      case LTE -> compare(values.get(0), values.get(1), operandTypes.get(0)) <= 0;
      case IN -> contains(values.get(1), values.get(0));
      case CONTAINS -> contains(values.get(0), values.get(1));
      case AND -> values.stream().allMatch(JsonNode::booleanValue);
      case OR -> values.stream().anyMatch(JsonNode::booleanValue);
      case NOT -> !values.getFirst().booleanValue();
      case IS_NULL -> throw new IllegalStateException("IS_NULL was handled before null policy");
    };
  }

  private static JsonNode resolve(JsonNode context, ReferencePath path) {
    JsonNode current = context;
    for (String segment : path.segments()) {
      if (!current.isObject() || !current.has(segment)) {
        return MissingNode.getInstance();
      }
      current = current.get(segment);
    }
    return current.deepCopy();
  }

  private static boolean contains(JsonNode container, JsonNode item) {
    if (container.isTextual() && item.isTextual()) {
      return container.textValue().contains(item.textValue());
    }
    if (container.isArray()) {
      for (JsonNode candidate : container) {
        if (candidate.equals(item)) {
          return true;
        }
      }
    }
    return false;
  }

  private static int compare(JsonNode left, JsonNode right, TypeDescriptor declaredType) {
    return switch (declaredType.type()) {
      case NUMBER, INTEGER -> left.decimalValue().compareTo(right.decimalValue());
      case MONEY -> compareMoney(left, right);
      case DATETIME -> Instant.parse(left.textValue()).compareTo(Instant.parse(right.textValue()));
      case DATE -> LocalDate.parse(left.textValue()).compareTo(LocalDate.parse(right.textValue()));
      case DURATION ->
          Duration.parse(left.textValue()).compareTo(Duration.parse(right.textValue()));
      case STRING, ENUM -> left.textValue().compareTo(right.textValue());
      default ->
          throw new IllegalStateException("Type is not ordered: " + declaredType.displayName());
    };
  }

  private static int compareMoney(JsonNode left, JsonNode right) {
    String leftCurrency = left.path("currency").textValue();
    String rightCurrency = right.path("currency").textValue();
    if (!leftCurrency.equals(rightCurrency)) {
      throw new IllegalStateException("Money values with different currencies are not comparable");
    }
    return new BigDecimal(left.path("amount").asText())
        .compareTo(new BigDecimal(right.path("amount").asText()));
  }

  private static TypeDescriptor compiledTypeOf(
      Expression expression, ExpressionSchema expressionSchema) {
    if (expression instanceof LiteralExpression literal) {
      return literal.type();
    }
    if (expression instanceof ReferenceExpression reference) {
      return expressionSchema
          .typeOf(reference.path())
          .orElseThrow(() -> new IllegalStateException("Compiled reference type is missing"));
    }
    return BOOLEAN;
  }

  private static boolean isNullish(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode();
  }

  private static void issue(
      List<ExpressionValidationIssue> issues, String code, String path, String message) {
    issues.add(new ExpressionValidationIssue(code, path, message));
  }
}
