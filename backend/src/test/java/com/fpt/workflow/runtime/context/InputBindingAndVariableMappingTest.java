package com.fpt.workflow.runtime.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.VariableScope;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.resolver.expression.LiteralExpression;
import com.fpt.workflow.resolver.expression.ReferenceExpression;
import com.fpt.workflow.resolver.expression.SafeExpressionEngine;
import com.fpt.workflow.runtime.binding.BindingException;
import com.fpt.workflow.runtime.binding.EventVariableMapper;
import com.fpt.workflow.runtime.binding.InputBinding;
import com.fpt.workflow.runtime.binding.InputBindingResolver;
import com.fpt.workflow.runtime.binding.MissingValueBehavior;
import com.fpt.workflow.runtime.binding.VariableMapping;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.security.masking.DefaultSensitiveValueMasker;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InputBindingAndVariableMappingTest {

  private static final TypeDescriptor INTEGER = TypeDescriptor.required(CanonicalValueType.INTEGER);
  private static final TypeDescriptor STRING = TypeDescriptor.required(CanonicalValueType.STRING);
  private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
  private final SafeExpressionEngine expressions = new SafeExpressionEngine();
  private final InputBindingResolver resolver = new InputBindingResolver(expressions);

  @Test
  void rejectsStaticallyIncompatibleBindingType() {
    EventContext context =
        context(JsonNodeFactory.instance.objectNode(), Map.of("ticket.score", INTEGER));
    InputBinding binding =
        new InputBinding(
            "reviewer",
            new ReferenceExpression("ticket.score"),
            STRING,
            true,
            MissingValueBehavior.ERROR,
            null);

    assertThatThrownBy(() -> resolver.resolve(List.of(binding), context))
        .isInstanceOf(BindingException.class)
        .extracting(exception -> ((BindingException) exception).code())
        .isEqualTo("INPUT_BINDING.TYPE_MISMATCH");
  }

  @Test
  void appliesConfiguredMissingDefaultAndEnforcesRequiredNull() {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    EventContext context = context(root, Map.of("ticket.optional", STRING.withNullable(true)));
    InputBinding withDefault =
        new InputBinding(
            "comment",
            new ReferenceExpression("ticket.optional"),
            STRING.withNullable(true),
            true,
            MissingValueBehavior.USE_DEFAULT,
            JsonNodeFactory.instance.textNode("n/a"));
    InputBinding requiredNull =
        new InputBinding(
            "comment",
            new ReferenceExpression("ticket.optional"),
            STRING.withNullable(true),
            true,
            MissingValueBehavior.NULL,
            null);

    assertThat(resolver.resolve(List.of(withDefault), context).path("comment").asText())
        .isEqualTo("n/a");
    assertThatThrownBy(() -> resolver.resolve(List.of(requiredNull), context))
        .isInstanceOf(BindingException.class)
        .hasMessageContaining("Required binding");
  }

  @Test
  void returnsAnImmutableInputSnapshotIndependentFromLaterContextChanges() {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.putObject("ticket").put("score", 7);
    EventContext context = context(root, Map.of("ticket.score", INTEGER));
    InputBinding binding =
        new InputBinding(
            "payload.score",
            new ReferenceExpression("ticket.score"),
            INTEGER,
            true,
            MissingValueBehavior.ERROR,
            null);

    ObjectNode snapshot = resolver.resolve(List.of(binding), context);
    root.withObject("ticket").put("score", 99);

    assertThat(snapshot.at("/payload/score").asInt()).isEqualTo(7);
    ObjectNode leaked = context.value();
    leaked.withObject("ticket").put("score", 101);
    assertThat(context.value().at("/ticket/score").asInt()).isEqualTo(7);
  }

  @Test
  void onlyExplicitMappingCanModifyAMutableDeclaredEventVariable() {
    UUID versionId = UUID.randomUUID();
    Event event = event(versionId, JsonNodeFactory.instance.objectNode().put("count", 1));
    WorkflowVariable count =
        WorkflowVariable.create(
            UUID.randomUUID(), versionId, "count", INTEGER, VariableScope.EVENT, null, true, false);
    EventContext context =
        context(
            JsonNodeFactory.instance
                .objectNode()
                .set("variables", JsonNodeFactory.instance.objectNode().put("count", 1)),
            Map.of("variables.count", INTEGER));
    VariableMapping mapping =
        new VariableMapping(
            "count", new LiteralExpression(JsonNodeFactory.instance.numberNode(2), INTEGER));

    ObjectNode changes =
        new EventVariableMapper(expressions)
            .apply(event, List.of(count), List.of(mapping), context);

    assertThat(changes.path("count").asInt()).isEqualTo(2);
    assertThat(event.getVariablesJson().path("count").asInt()).isEqualTo(2);
    assertThat(event.getVariablesJson()).doesNotHaveToString("{}");
  }

  @Test
  void resolvedSnapshotReplaysIdenticallyAfterAuthoritativeContextAdvances() {
    ObjectNode oldRoot = JsonNodeFactory.instance.objectNode();
    oldRoot.putObject("ticket").put("amount", 10);
    InputBinding binding =
        new InputBinding(
            "amount",
            new ReferenceExpression("ticket.amount"),
            INTEGER,
            true,
            MissingValueBehavior.ERROR,
            null);
    ObjectNode input =
        resolver.resolve(List.of(binding), context(oldRoot, Map.of("ticket.amount", INTEGER)));

    ObjectNode newRoot = JsonNodeFactory.instance.objectNode();
    newRoot.putObject("ticket").put("amount", 20);
    ObjectNode current =
        resolver.resolve(List.of(binding), context(newRoot, Map.of("ticket.amount", INTEGER)));

    assertThat(input.path("amount").asInt()).isEqualTo(10);
    assertThat(current.path("amount").asInt()).isEqualTo(20);
    assertThat(input.path("amount").asInt()).isEqualTo(10);
  }

  private EventContext context(ObjectNode value, Map<String, TypeDescriptor> paths) {
    return new EventContext(
        value, new ExpressionSchema(paths, Set.of()), List.of(), new DefaultSensitiveValueMasker());
  }

  private Event event(UUID versionId, ObjectNode variables) {
    UUID eventId = UUID.randomUUID();
    return Event.createRoot(
        eventId,
        UUID.randomUUID(),
        versionId,
        UUID.randomUUID(),
        null,
        null,
        "TEST",
        "mapping",
        variables,
        UUID.randomUUID(),
        NOW);
  }
}
