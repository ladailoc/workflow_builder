package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.resolver.expression.*;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.*;

/** Evaluates the closed expression AST only against the immutable activation input snapshot. */
public final class ConditionNodeHandler implements NodeHandler {
  private final ObjectMapper mapper;
  private final ExpressionEngine expressions;

  public ConditionNodeHandler(ObjectMapper mapper, ExpressionEngine expressions) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.expressions = Objects.requireNonNull(expressions, "expressions");
  }

  @Override
  public NodeType supports() {
    return NodeType.CONDITION;
  }

  @Override
  public NodeExecutionResult execute(NodeHandlerContext context) {
    try {
      Expression expression =
          mapper.treeToValue(context.configuration().get("expression"), Expression.class);
      Map<String, TypeDescriptor> paths = new HashMap<>();
      inferPaths("", context.input(), paths);
      inferReferenceTypes(expression, paths);
      CompiledExpression compiled =
          expressions.compile(
              expression, ExpressionScope.RUNTIME, new ExpressionSchema(paths, Set.of()));
      boolean matched =
          expressions.evaluate(compiled, context.input(), NullPolicy.NULL_IS_FALSE).booleanValue();
      return NodeExecutionResult.complete(context.input(), matched ? "TRUE" : "FALSE");
    } catch (Exception exception) {
      var details = mapper.createObjectNode();
      details.put("exception", exception.getClass().getSimpleName());
      return NodeExecutionResult.fail(
          new NodeExecutionError(
              "CONDITION.EVALUATION_FAILED", "Condition evaluation failed", details));
    }
  }

  private void inferReferenceTypes(Expression expression, Map<String, TypeDescriptor> paths) {
    if (!(expression instanceof OperatorExpression operator)) return;
    if (operator.operands().size() == 2) {
      Expression left = operator.operands().get(0);
      Expression right = operator.operands().get(1);
      if (left instanceof ReferenceExpression reference
          && right instanceof LiteralExpression literal) {
        paths.put(reference.path().value(), literal.type().withNullable(true));
      }
      if (right instanceof ReferenceExpression reference
          && left instanceof LiteralExpression literal) {
        paths.put(reference.path().value(), literal.type().withNullable(true));
      }
    }
    operator.operands().forEach(operand -> inferReferenceTypes(operand, paths));
  }

  private void inferPaths(String prefix, JsonNode value, Map<String, TypeDescriptor> paths) {
    if (value == null || value.isNull()) return;
    if (value.isObject()) {
      value
          .fields()
          .forEachRemaining(
              entry ->
                  inferPaths(
                      prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(),
                      entry.getValue(),
                      paths));
      return;
    }
    if (!prefix.isEmpty()) paths.put(prefix, typeOf(value));
  }

  private TypeDescriptor typeOf(JsonNode value) {
    CanonicalValueType type =
        value.isBoolean()
            ? CanonicalValueType.BOOLEAN
            : value.isIntegralNumber()
                ? CanonicalValueType.INTEGER
                : value.isNumber()
                    ? CanonicalValueType.NUMBER
                    : value.isArray() ? CanonicalValueType.ARRAY : CanonicalValueType.STRING;
    if (type == CanonicalValueType.ARRAY) {
      TypeDescriptor item =
          value.isEmpty()
              ? TypeDescriptor.nullable(CanonicalValueType.OBJECT)
              : typeOf(value.get(0));
      return TypeDescriptor.arrayOf(item);
    }
    return TypeDescriptor.required(type);
  }
}
