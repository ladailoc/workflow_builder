package com.fpt.workflow.form.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.resolver.expression.ExpressionOperator;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.resolver.expression.LiteralExpression;
import com.fpt.workflow.resolver.expression.OperatorExpression;
import com.fpt.workflow.resolver.expression.ReferenceExpression;
import com.fpt.workflow.resolver.expression.SafeExpressionEngine;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CanonicalFormEngineTest {

  private CanonicalFormEngine engine;
  private ExpressionSchema contextSchema;

  @BeforeEach
  void setUp() {
    engine = new CanonicalFormEngine(new SafeExpressionEngine());
    contextSchema =
        new ExpressionSchema(
            Map.of(
                "actor.id",
                type(CanonicalValueType.USER_ID),
                "organization.departmentId",
                type(CanonicalValueType.DEPARTMENT_ID),
                "requestType.key",
                type(CanonicalValueType.STRING)),
            Set.of());
  }

  @Test
  void draftMayBeIncompleteButPublishBlocksIncompleteConditionalField() {
    FormFieldDefinition incomplete =
        field(
            "quotation",
            "",
            type(CanonicalValueType.FILE_REF),
            new FieldRequirement(FieldRequirementMode.CONDITIONAL, null),
            FieldVisibility.always(),
            false,
            FieldSemanticMetadata.none());
    FormSchema schema = FormSchema.ticketForm("request", List.of(incomplete));

    FormValidationResult draft =
        engine.validateSchema(schema, FormValidationPhase.DRAFT_SAVE, contextSchema);
    FormValidationResult publish =
        engine.validateSchema(schema, FormValidationPhase.PUBLISH, contextSchema);

    assertThat(draft.valid()).isTrue();
    assertThat(draft.issues()).allMatch(issue -> issue.severity() == FormIssueSeverity.WARNING);
    assertThat(publish.valid()).isFalse();
    assertThat(publish.issues())
        .extracting(FormValidationIssue::code)
        .contains("FORM.FIELD_LABEL_MISSING", "FORM.CONDITION_MISSING");
  }

  @Test
  void dynamicRequirementUsesAnExistingTypedFormField() {
    FormFieldDefinition amount =
        field(
            "amount",
            "Amount",
            type(CanonicalValueType.INTEGER),
            FieldRequirement.always(),
            FieldVisibility.always(),
            false,
            FieldSemanticMetadata.none());
    var requirement =
        FieldRequirement.conditional(
            OperatorExpression.of(
                ExpressionOperator.GT,
                new ReferenceExpression("form.amount"),
                new LiteralExpression(
                    JsonNodeFactory.instance.numberNode(100), type(CanonicalValueType.INTEGER))));
    FormFieldDefinition quotation =
        field(
            "quotation",
            "Quotation",
            type(CanonicalValueType.FILE_REF),
            requirement,
            FieldVisibility.always(),
            false,
            FieldSemanticMetadata.none());
    FormSchema schema = FormSchema.ticketForm("request", List.of(amount, quotation));
    var context = JsonNodeFactory.instance.objectNode();

    FormValidationResult expensive =
        engine.validateSubmission(
            schema,
            JsonNodeFactory.instance.objectNode().put("amount", 101),
            context,
            contextSchema);
    FormValidationResult inexpensive =
        engine.validateSubmission(
            schema,
            JsonNodeFactory.instance.objectNode().put("amount", 50),
            context,
            contextSchema);

    assertThat(expensive.issues())
        .extracting(FormValidationIssue::code)
        .contains("FORM.REQUIRED_FIELD_MISSING");
    assertThat(inexpensive.valid()).isTrue();
  }

  @Test
  void enforcesVisibilityTypesAndRejectsCreatorSuppliedArbitraryFields() {
    FormFieldDefinition score =
        field(
            "score",
            "Score",
            type(CanonicalValueType.INTEGER),
            FieldRequirement.never(),
            FieldVisibility.conditional(
                OperatorExpression.of(
                    ExpressionOperator.EQ,
                    new ReferenceExpression("requestType.key"),
                    new LiteralExpression(
                        JsonNodeFactory.instance.textNode("VISIBLE"),
                        type(CanonicalValueType.STRING)))),
            false,
            FieldSemanticMetadata.none());
    FormSchema schema = FormSchema.ticketForm("request", List.of(score));
    var context = JsonNodeFactory.instance.objectNode();
    context.putObject("requestType").put("key", "HIDDEN");
    var submitted = JsonNodeFactory.instance.objectNode();
    submitted.put("score", "not-an-integer");
    submitted.put("creatorInventedField", "forbidden");

    FormValidationResult result =
        engine.validateSubmission(schema, submitted, context, contextSchema);

    assertThat(result.issues())
        .extracting(FormValidationIssue::code)
        .contains("FORM.UNKNOWN_FIELD", "FORM.FIELD_NOT_VISIBLE", "TYPE.INTEGER_REQUIRED");
  }

  @Test
  void sensitiveFieldsCannotLeakThroughDefaultsOrSearchMetadata() {
    var sensitiveMetadata = new FieldSemanticMetadata(false, false, true, true, true);
    FormFieldDefinition secret =
        new FormFieldDefinition(
            UUID.randomUUID(),
            "secret",
            "Secret",
            null,
            null,
            0,
            type(CanonicalValueType.STRING),
            JsonNodeFactory.instance.textNode("embedded-secret"),
            true,
            FieldRequirement.never(),
            FieldVisibility.always(),
            FieldEditability.editable(),
            FieldValidationRules.none(),
            null,
            sensitiveMetadata);

    FormValidationResult result =
        engine.validateSchema(
            FormSchema.taskForm("approval", List.of(secret)),
            FormValidationPhase.PUBLISH,
            contextSchema);

    assertThat(result.issues())
        .extracting(FormValidationIssue::code)
        .contains("FORM.SENSITIVE_DEFAULT_FORBIDDEN", "FORM.SENSITIVE_INDEXING_FORBIDDEN");
  }

  @Test
  void taskFormConditionsUseRuntimeContextWithoutLosingSubmittedFormScope() {
    ExpressionSchema runtimeSchema =
        new ExpressionSchema(
            Map.of(
                "ticket.data.amount", type(CanonicalValueType.INTEGER),
                "task.outcome", type(CanonicalValueType.STRING)),
            Set.of());
    FormFieldDefinition comment =
        field(
            "comment",
            "Comment",
            type(CanonicalValueType.STRING),
            FieldRequirement.conditional(
                OperatorExpression.of(
                    ExpressionOperator.GT,
                    new ReferenceExpression("ticket.data.amount"),
                    new LiteralExpression(
                        JsonNodeFactory.instance.numberNode(100),
                        type(CanonicalValueType.INTEGER)))),
            FieldVisibility.always(),
            false,
            FieldSemanticMetadata.none());
    FormSchema schema = FormSchema.taskForm("approval", List.of(comment));
    var context = JsonNodeFactory.instance.objectNode();
    context.putObject("ticket").putObject("data").put("amount", 101);

    FormValidationResult result =
        engine.validateSubmission(
            schema, JsonNodeFactory.instance.objectNode(), context, runtimeSchema);

    assertThat(result.issues())
        .extracting(FormValidationIssue::code)
        .contains("FORM.REQUIRED_FIELD_MISSING")
        .doesNotContain("EXPRESSION.NAMESPACE_NOT_ALLOWED");
  }

  @Test
  void validatesSafeRulesOptionsLengthAndReferenceSemantics() {
    FieldValidationRules rules =
        new FieldValidationRules(
            null,
            null,
            2,
            4,
            new SafeRegex("^[A-Z]+$"),
            List.of(
                OperatorExpression.of(
                    ExpressionOperator.CONTAINS,
                    new ReferenceExpression("form.code"),
                    new LiteralExpression(
                        JsonNodeFactory.instance.textNode("A"), type(CanonicalValueType.STRING)))));
    FormFieldDefinition code =
        new FormFieldDefinition(
            UUID.randomUUID(),
            "code",
            "Code",
            null,
            null,
            0,
            type(CanonicalValueType.ENUM),
            null,
            false,
            FieldRequirement.always(),
            FieldVisibility.always(),
            FieldEditability.editable(),
            rules,
            FieldOptions.staticValues(
                List.of(
                    JsonNodeFactory.instance.textNode("AB"),
                    JsonNodeFactory.instance.textNode("CD"))),
            new FieldSemanticMetadata(true, true, false, false, false));
    FormSchema schema = FormSchema.taskForm("review", List.of(code));

    FormValidationResult schemaResult =
        engine.validateSchema(schema, FormValidationPhase.PUBLISH, contextSchema);
    FormValidationResult valueResult =
        engine.validateSubmission(
            schema,
            JsonNodeFactory.instance.objectNode().put("code", "ZZ"),
            JsonNodeFactory.instance.objectNode(),
            contextSchema);

    assertThat(schemaResult.issues())
        .extracting(FormValidationIssue::code)
        .contains("FORM.REFERENCE_SEMANTIC_TYPE_REQUIRED");
    assertThat(valueResult.issues())
        .extracting(FormValidationIssue::code)
        .contains("FORM.VALUE_NOT_IN_OPTIONS", "FORM.SAFE_RULE_FAILED");
    assertThatThrownBy(() -> FieldOptions.dataSource("https://attacker.example/options"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SafeRegex("(a+)+")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void formSchemaAndTypedExpressionRoundTripAsJson() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    FormSchema original =
        FormSchema.ticketForm(
            "request",
            List.of(
                field(
                    "name",
                    "Name",
                    type(CanonicalValueType.STRING),
                    FieldRequirement.always(),
                    FieldVisibility.always(),
                    false,
                    FieldSemanticMetadata.none())));

    FormSchema restored = mapper.readValue(mapper.writeValueAsBytes(original), FormSchema.class);

    assertThat(restored).usingRecursiveComparison().isEqualTo(original);
  }

  private static FormFieldDefinition field(
      String key,
      String label,
      TypeDescriptor type,
      FieldRequirement requirement,
      FieldVisibility visibility,
      boolean sensitive,
      FieldSemanticMetadata semantics) {
    return new FormFieldDefinition(
        UUID.randomUUID(),
        key,
        label,
        null,
        null,
        0,
        type,
        null,
        sensitive,
        requirement,
        visibility,
        FieldEditability.editable(),
        new FieldValidationRules(null, null, null, null, null, List.of()),
        null,
        semantics);
  }

  private static TypeDescriptor type(CanonicalValueType type) {
    return TypeDescriptor.required(type);
  }
}
