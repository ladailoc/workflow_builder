package com.fpt.workflow.runtime.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.VariableScope;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.security.masking.DefaultSensitiveValueMasker;
import com.fpt.workflow.shared.domain.value.CanonicalSchema;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventContextBuilderTest {

  private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final EventRepository events = mock(EventRepository.class);
  private final NodeExecutionRepository executions = mock(NodeExecutionRepository.class);
  private final NodeDefinitionRepository nodes = mock(NodeDefinitionRepository.class);
  private final WorkflowVariableRepository variables = mock(WorkflowVariableRepository.class);
  private final WorkflowFormRepository forms = mock(WorkflowFormRepository.class);
  private final TicketContextSource tickets = mock(TicketContextSource.class);
  private final ActorContextProvider actors = mock(ActorContextProvider.class);
  private EventContextBuilder builder;
  private UUID eventId;
  private UUID versionId;
  private UUID revisionId;
  private UUID nodeId;
  private UUID cycleId;

  @BeforeEach
  void setUp() {
    eventId = UUID.randomUUID();
    versionId = UUID.randomUUID();
    revisionId = UUID.randomUUID();
    nodeId = UUID.randomUUID();
    cycleId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID creatorId = UUID.randomUUID();
    ObjectNode storedVariables = mapper.createObjectNode().put("secret", "alpha");
    Event event =
        Event.createRoot(
            eventId,
            ticketId,
            versionId,
            revisionId,
            null,
            null,
            "USER_SUBMIT",
            "context-test",
            storedVariables,
            creatorId,
            NOW);
    when(events.findById(eventId)).thenReturn(Optional.of(event));
    when(tickets.load(ticketId, revisionId))
        .thenReturn(
            new TicketContextSnapshot(
                ticketId,
                UUID.randomUUID(),
                creatorId,
                "SUBMITTED",
                2,
                UUID.randomUUID(),
                mapper.createObjectNode().put("amount", 200),
                revisionId,
                1,
                mapper.createObjectNode().put("amount", 100),
                "1",
                "form-checksum",
                NOW,
                List.of(new TicketSubjectSnapshot("USER", creatorId, "REQUESTER", "requester"))));
    WorkflowVariable secret =
        WorkflowVariable.create(
            UUID.randomUUID(),
            versionId,
            "secret",
            TypeDescriptor.required(CanonicalValueType.STRING),
            VariableScope.EVENT,
            null,
            true,
            true);
    when(variables.findAllByWorkflowVersionIdOrderByKeyAsc(versionId)).thenReturn(List.of(secret));
    NodeDefinition node =
        NodeDefinition.create(
            nodeId,
            versionId,
            "review",
            "REVIEW",
            "Review",
            null,
            1,
            mapper.createObjectNode(),
            null,
            mapper.valueToTree(
                CanonicalSchema.strict(
                    Map.of("score", TypeDescriptor.required(CanonicalValueType.INTEGER)),
                    Set.of("score"))),
            mapper.createObjectNode().put("x", 0).put("y", 0));
    when(nodes.findAllByWorkflowVersionIdOrderByNodeKeyAsc(versionId)).thenReturn(List.of(node));
    when(forms.findAllByWorkflowVersionIdOrderByFormKeyAsc(versionId)).thenReturn(List.of());
    when(actors.currentActor())
        .thenReturn(
            Optional.of(new ActorContext(creatorId, "creator", Set.of(RoleKey.USER), Set.of())));
    builder =
        new EventContextBuilder(
            events,
            executions,
            nodes,
            variables,
            forms,
            tickets,
            actors,
            new CoreEventContextNamespaceProvider(),
            new DefaultSensitiveValueMasker(),
            mapper);
  }

  @Test
  void buildsAuthoritativeTicketRevisionVariableActorAndSubjectNamespaces() {
    when(executions.findAllByEventIdOrderByCreatedAtAsc(eventId)).thenReturn(List.of());

    EventContext context = builder.build(eventId);

    assertThat(context.value().at("/ticket/data/amount").asInt()).isEqualTo(100);
    assertThat(context.value().at("/ticket/current/data/amount").asInt()).isEqualTo(200);
    assertThat(context.value().at("/ticket/subjects/0/role").asText()).isEqualTo("REQUESTER");
    assertThat(context.value().at("/variables/secret").asText()).isEqualTo("alpha");
    assertThat(context.value().at("/actor/principal").asText()).isEqualTo("creator");
  }

  @Test
  void exposesLatestCompletedOccurrenceAndAllOccurrencesWithoutCollapsingHistory() {
    NodeExecution first = completed("one", cycleId, "root", null, 1, NOW.plusSeconds(1));
    NodeExecution second = completed("two", cycleId, "root", null, 2, NOW.plusSeconds(2));
    when(executions.findAllByEventIdOrderByCreatedAtAsc(eventId))
        .thenReturn(List.of(first, second));

    EventContext context = builder.build(eventId);

    assertThat(context.value().at("/nodes/review/executions")).hasSize(2);
    assertThat(context.value().at("/nodes/review/latest/output/score").asInt()).isEqualTo(2);
    assertThat(
            context
                .expressionSchema()
                .isAmbiguousRepeatedNodeReference(
                    new com.fpt.workflow.resolver.expression.ReferencePath(
                        "nodes.review.output.score")))
        .isTrue();
  }

  @Test
  void limitsLatestAndExecutionsToCyclePathAndItemScope() {
    NodeExecution ancestor = completed("ancestor", cycleId, "root", null, 1, NOW.plusSeconds(1));
    NodeExecution current =
        completed("current", cycleId, "root/branch", "item-a", 2, NOW.plusSeconds(2));
    NodeExecution sibling =
        completed("sibling", cycleId, "root/other", "item-a", 3, NOW.plusSeconds(3));
    NodeExecution otherItem =
        completed("item-b", cycleId, "root/branch", "item-b", 4, NOW.plusSeconds(4));
    when(executions.findAllByEventIdOrderByCreatedAtAsc(eventId))
        .thenReturn(List.of(ancestor, current, sibling, otherItem));

    EventContext context =
        builder.build(eventId, RuntimeScope.occurrence(cycleId, "root/branch", "item-a"));

    assertThat(context.value().at("/nodes/review/executions")).hasSize(2);
    assertThat(context.value().at("/nodes/review/latest/output/score").asInt()).isEqualTo(2);
    assertThat(context.value().at("/nodes/review/items/item-b")).hasSize(1);
  }

  @Test
  void carriesOptionalItemAndTaskScopeWithoutPersistingACombinedContext() {
    when(executions.findAllByEventIdOrderByCreatedAtAsc(eventId)).thenReturn(List.of());
    RuntimeScope scope =
        new RuntimeScope(
            cycleId,
            "root",
            "item-a",
            mapper.createObjectNode().put("sku", "A-1"),
            Map.of("sku", TypeDescriptor.required(CanonicalValueType.STRING)),
            mapper.createObjectNode().put("decision", "APPROVE"),
            Map.of("decision", TypeDescriptor.required(CanonicalValueType.STRING)));

    EventContext context = builder.build(eventId, scope);

    assertThat(context.value().at("/item/sku").asText()).isEqualTo("A-1");
    assertThat(context.value().at("/task/decision").asText()).isEqualTo("APPROVE");
    assertThat(context.expressionSchema().pathTypes()).containsKeys("item.sku", "task.decision");
  }

  @Test
  void carriesSensitiveMetadataAndReturnsASeparatelyMaskedProjection() {
    when(executions.findAllByEventIdOrderByCreatedAtAsc(eventId)).thenReturn(List.of());

    EventContext context = builder.build(eventId);

    assertThat(context.sensitiveValues())
        .extracting(SensitiveValueMetadata::path)
        .containsExactly("variables.secret");
    assertThat(context.value().at("/variables/secret").asText()).isEqualTo("alpha");
    assertThat(context.maskedValue().at("/variables/secret").asText()).isEqualTo("[REDACTED]");
  }

  private NodeExecution completed(
      String activation, UUID cycle, String path, String item, int score, Instant createdAt) {
    NodeExecution execution =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            nodeId,
            activation,
            cycle,
            score,
            path,
            item,
            null,
            null,
            mapper.createObjectNode(),
            revisionId,
            createdAt);
    execution.markReady();
    execution.start(createdAt);
    execution.complete("DONE", mapper.createObjectNode().put("score", score), createdAt);
    return execution;
  }
}
