package com.fpt.workflow.resolver.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SafeExpressionEngineTest {

  private static final TypeDescriptor BOOLEAN = type(CanonicalValueType.BOOLEAN);
  private static final TypeDescriptor INTEGER = type(CanonicalValueType.INTEGER);
  private static final TypeDescriptor MONEY = type(CanonicalValueType.MONEY);
  private static final TypeDescriptor STRING = type(CanonicalValueType.STRING);

  private SafeExpressionEngine engine;
  private ExpressionSchema schema;

  @BeforeEach
  void setUp() {
    engine = new SafeExpressionEngine();
    schema =
        new ExpressionSchema(
            Map.ofEntries(
                Map.entry("form.amount", MONEY),
                Map.entry("actor.id", type(CanonicalValueType.USER_ID)),
                Map.entry("organization.departmentId", type(CanonicalValueType.DEPARTMENT_ID)),
                Map.entry("requestType.key", STRING),
                Map.entry("ticket.data.amount", MONEY),
                Map.entry("ticket.data.score", INTEGER),
                Map.entry("variables.approved", BOOLEAN),
                Map.entry("nodes.review.output.score", INTEGER),
                Map.entry("nodes.review.latest.output.score", INTEGER),
                Map.entry(
                    "nodes.review.executions",
                    TypeDescriptor.arrayOf(type(CanonicalValueType.OBJECT))),
                Map.entry("nodes.review.items.current.output.score", INTEGER),
                Map.entry("nodes.review.cycles.current.latest.output.score", INTEGER),
                Map.entry("item.score", INTEGER),
                Map.entry("task.outcome", STRING)),
            Set.of("review"));
  }

  @Test
  void validatesTypingAndEvaluatesAllowlistedOperators() {
    Expression expression =
        OperatorExpression.of(
            ExpressionOperator.AND,
            OperatorExpression.of(
                ExpressionOperator.GTE,
                new ReferenceExpression("${ticket.data.score}"),
                literal(8)),
            new ReferenceExpression("variables.approved"));
    var context = JsonNodeFactory.instance.objectNode();
    context.putObject("ticket").putObject("data").put("score", 9);
    context.putObject("variables").put("approved", true);

    CompiledExpression compiled = engine.compile(expression, ExpressionScope.RUNTIME, schema);

    assertThat(compiled.resultType()).isEqualTo(BOOLEAN);
    assertThat(engine.evaluate(compiled, context, NullPolicy.ERROR).booleanValue()).isTrue();
  }

  @Test
  void rejectsInvalidTypeAssignmentsAndInvalidArity() {
    Expression invalidType =
        OperatorExpression.of(
            ExpressionOperator.GT, new ReferenceExpression("task.outcome"), literal(2));
    Expression invalidArity =
        OperatorExpression.of(ExpressionOperator.NOT, literal(true), literal(false));

    assertThat(engine.validate(invalidType, ExpressionScope.RUNTIME, schema).issues())
        .extracting(ExpressionValidationIssue::code)
        .contains("EXPRESSION.INCOMPATIBLE_TYPES");
    assertThat(engine.validate(invalidArity, ExpressionScope.RUNTIME, schema).issues())
        .extracting(ExpressionValidationIssue::code)
        .contains("EXPRESSION.INVALID_ARITY");
  }

  @Test
  void enforcesDesignTimeAndRuntimeNamespacePoliciesAndStaticPaths() {
    var runtimeReference = new ReferenceExpression("ticket.data.score");
    var unknownFormPath = new ReferenceExpression("form.undeclared");

    assertThat(engine.validate(runtimeReference, ExpressionScope.TICKET_FORM, schema).issues())
        .extracting(ExpressionValidationIssue::code)
        .containsExactly("EXPRESSION.NAMESPACE_NOT_ALLOWED");
    assertThat(engine.validate(unknownFormPath, ExpressionScope.TICKET_FORM, schema).issues())
        .extracting(ExpressionValidationIssue::code)
        .containsExactly("EXPRESSION.UNKNOWN_REFERENCE_PATH");
    assertThat(
            engine
                .validate(
                    new ReferenceExpression("form.amount"), ExpressionScope.TICKET_FORM, schema)
                .valid())
        .isTrue();
  }

  @Test
  void repeatedNodeDirectOutputIsAmbiguousButExplicitAddressesAreValid() {
    assertThat(
            engine
                .validate(
                    new ReferenceExpression("nodes.review.output.score"),
                    ExpressionScope.RUNTIME,
                    schema)
                .issues())
        .extracting(ExpressionValidationIssue::code)
        .containsExactly("EXPRESSION.REPEATED_NODE_REFERENCE_AMBIGUOUS");

    assertThat(
            engine
                .validate(
                    new ReferenceExpression("nodes.review.latest.output.score"),
                    ExpressionScope.RUNTIME,
                    schema)
                .valid())
        .isTrue();
    assertThat(
            engine
                .validate(
                    new ReferenceExpression("nodes.review.executions"),
                    ExpressionScope.RUNTIME,
                    schema)
                .valid())
        .isTrue();
    assertThat(
            engine
                .validate(
                    new ReferenceExpression("nodes.review.items.current.output.score"),
                    ExpressionScope.RUNTIME,
                    schema)
                .valid())
        .isTrue();
    assertThat(
            engine
                .validate(
                    new ReferenceExpression("nodes.review.cycles.current.latest.output.score"),
                    ExpressionScope.RUNTIME,
                    schema)
                .valid())
        .isTrue();
  }

  @Test
  void nullPolicyIsExplicitAndIsNullIsSafe() {
    Expression equalsMissing =
        OperatorExpression.of(
            ExpressionOperator.EQ, new ReferenceExpression("task.outcome"), literal("APPROVED"));
    CompiledExpression compiled = engine.compile(equalsMissing, ExpressionScope.RUNTIME, schema);
    var emptyContext = JsonNodeFactory.instance.objectNode();

    assertThat(engine.evaluate(compiled, emptyContext, NullPolicy.NULL_IS_FALSE).booleanValue())
        .isFalse();
    assertThatThrownBy(() -> engine.evaluate(compiled, emptyContext, NullPolicy.ERROR))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Null or missing");

    CompiledExpression isNull =
        engine.compile(
            OperatorExpression.of(
                ExpressionOperator.IS_NULL, new ReferenceExpression("task.outcome")),
            ExpressionScope.RUNTIME,
            schema);
    assertThat(engine.evaluate(isNull, emptyContext, NullPolicy.ERROR).booleanValue()).isTrue();
  }

  @Test
  void supportsMembershipContainsMoneyDateAndDatetimeComparisons() {
    Expression contains =
        OperatorExpression.of(
            ExpressionOperator.CONTAINS,
            new LiteralExpression(
                JsonNodeFactory.instance.arrayNode().add("A").add("B"),
                TypeDescriptor.arrayOf(STRING)),
            literal("B"));
    assertThat(
            engine
                .evaluate(
                    engine.compile(contains, ExpressionScope.RUNTIME, schema),
                    JsonNodeFactory.instance.objectNode(),
                    NullPolicy.ERROR)
                .booleanValue())
        .isTrue();

    assertOrderedComparison(type(CanonicalValueType.DATE), "2026-09-08", "2026-09-07");
    assertOrderedComparison(
        type(CanonicalValueType.DATETIME), "2026-09-08T00:00:00Z", "2026-09-07T00:00:00Z");

    var expensive = JsonNodeFactory.instance.objectNode().put("amount", 12).put("currency", "USD");
    var cheap = JsonNodeFactory.instance.objectNode().put("amount", 10).put("currency", "USD");
    Expression moneyComparison =
        OperatorExpression.of(
            ExpressionOperator.GT,
            new LiteralExpression(expensive, MONEY),
            new LiteralExpression(cheap, MONEY));
    assertThat(evaluateBoolean(moneyComparison)).isTrue();
  }

  @Test
  void orderedComparisonUsesTheCompiledTypeInsteadOfGuessingFromTextShape() {
    Expression stringComparison =
        OperatorExpression.of(ExpressionOperator.GT, literal("T9Z"), literal("T10Z"));

    assertThat(evaluateBoolean(stringComparison)).isTrue();
  }

  @Test
  void rejectsCodeInjectionAndNonAllowlistedOperatorNamesAtConstructionBoundary() {
    assertThatThrownBy(() -> new ReferencePath("T(java.lang.Runtime).getRuntime"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReferencePath("ticket.data; DROP TABLE tickets"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReferencePath("javascript:alert.actor"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExpressionOperator.valueOf("EXEC"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void assertOrderedComparison(TypeDescriptor type, String later, String earlier) {
    Expression expression =
        OperatorExpression.of(
            ExpressionOperator.GT,
            new LiteralExpression(JsonNodeFactory.instance.textNode(later), type),
            new LiteralExpression(JsonNodeFactory.instance.textNode(earlier), type));
    assertThat(evaluateBoolean(expression)).isTrue();
  }

  private boolean evaluateBoolean(Expression expression) {
    return engine
        .evaluate(
            engine.compile(expression, ExpressionScope.RUNTIME, schema),
            JsonNodeFactory.instance.objectNode(),
            NullPolicy.ERROR)
        .booleanValue();
  }

  private static LiteralExpression literal(int value) {
    return new LiteralExpression(JsonNodeFactory.instance.numberNode(value), INTEGER);
  }

  private static LiteralExpression literal(boolean value) {
    return new LiteralExpression(JsonNodeFactory.instance.booleanNode(value), BOOLEAN);
  }

  private static LiteralExpression literal(String value) {
    return new LiteralExpression(JsonNodeFactory.instance.textNode(value), STRING);
  }

  private static TypeDescriptor type(CanonicalValueType type) {
    return TypeDescriptor.required(type);
  }
}
