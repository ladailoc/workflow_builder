package com.fpt.workflow.runtime.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.nodetype.NodeCapability;
import com.fpt.workflow.nodetype.NodeHandler;
import com.fpt.workflow.nodetype.NodeHandlerContext;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeProvider;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.nodetype.StrictNodeValidator;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.resolver.expression.ExpressionOperator;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.resolver.expression.LiteralExpression;
import com.fpt.workflow.resolver.expression.OperatorExpression;
import com.fpt.workflow.resolver.expression.ReferenceExpression;
import com.fpt.workflow.resolver.expression.SafeExpressionEngine;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.context.EventContextBuilder;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
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
import org.mockito.ArgumentCaptor;

class RoutingServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
  private static final TypeDescriptor INTEGER = TypeDescriptor.required(CanonicalValueType.INTEGER);
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final NodeExecutionRepository executions = mock(NodeExecutionRepository.class);
  private final EventRepository events = mock(EventRepository.class);
  private final NodeDefinitionRepository nodes = mock(NodeDefinitionRepository.class);
  private final EdgeDefinitionRepository edges = mock(EdgeDefinitionRepository.class);
  private final EventContextBuilder contexts = mock(EventContextBuilder.class);
  private final NodeActivationService activations = mock(NodeActivationService.class);
  private final AuditEventRepository audits = mock(AuditEventRepository.class);
  private final ActorContextProvider actors = mock(ActorContextProvider.class);
  private RoutingService service;
  private Event event;
  private NodeExecution source;
  private NodeDefinition sourceNode;
  private UUID versionId;
  private UUID sourceNodeId;

  @BeforeEach
  void setUp() {
    versionId = UUID.randomUUID();
    sourceNodeId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    event =
        Event.createRoot(
            eventId,
            UUID.randomUUID(),
            versionId,
            UUID.randomUUID(),
            null,
            null,
            "TEST",
            "routing",
            mapper.createObjectNode().put("score", 5),
            UUID.randomUUID(),
            NOW);
    event.markRunning();
    sourceNode = node(RoutingMode.EXCLUSIVE_CONDITIONAL);
    source =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            sourceNodeId,
            "source-activation",
            UUID.randomUUID(),
            0,
            "root",
            null,
            null,
            null,
            mapper.createObjectNode(),
            event.getStartedTicketRevisionId(),
            NOW);
    source.markReady();
    source.start(NOW);
    source.complete("APPROVED", mapper.createObjectNode(), NOW);
    when(executions.findByIdForUpdate(source.getId())).thenReturn(Optional.of(source));
    when(events.findById(eventId)).thenReturn(Optional.of(event));
    when(nodes.findById(sourceNodeId)).thenReturn(Optional.of(sourceNode));
    when(actors.currentActor()).thenReturn(Optional.empty());
    when(activations.activate(any())).thenReturn(mock(NodeExecution.class));
    EventContext context = mock(EventContext.class);
    when(context.value())
        .thenReturn(
            JsonNodeFactory.instance
                .objectNode()
                .set("variables", JsonNodeFactory.instance.objectNode().put("score", 5)));
    when(context.expressionSchema())
        .thenReturn(new ExpressionSchema(Map.of("variables.score", INTEGER), Set.of()));
    when(contexts.build(eq(eventId), any())).thenReturn(context);

    CanonicalSchema empty = CanonicalSchema.strict(Map.of(), Set.of());
    NodeHandler handler = mock(NodeHandler.class);
    when(handler.supports()).thenReturn(NodeType.CONDITION);
    NodeTypeManifest manifest =
        new NodeTypeManifest(
            NodeType.CONDITION,
            1,
            Set.of(NodeCapability.ROUTING),
            empty,
            empty,
            Set.of("APPROVED"),
            empty,
            mapper.createObjectNode(),
            new StrictNodeValidator(empty),
            handler);
    service =
        new RoutingService(
            executions,
            events,
            nodes,
            edges,
            contexts,
            new SafeExpressionEngine(),
            new NodeTypeRegistry(List.of((NodeTypeProvider) () -> manifest)),
            activations,
            audits,
            actors,
            UUID::randomUUID,
            () -> NOW,
            mapper);
  }

  @Test
  void queriesOnlyTheCompletedOutcomePortAndUsesTheEdgeTarget() {
    UUID target = UUID.randomUUID();
    EdgeDefinition selected = edge(0, false, target, trueExpression());
    outgoing().thenReturn(List.of(selected));

    RoutingResult result = service.route(source.getId(), correlation(), command());

    assertThat(result.selectedEdgeIds()).containsExactly(selected.getId());
    verify(edges)
        .findAllByWorkflowVersionIdAndSourceNodeIdAndSourcePortOrderByPriorityAscIdAsc(
            versionId, sourceNodeId, "APPROVED");
    ArgumentCaptor<ActivationRequest> request = ArgumentCaptor.forClass(ActivationRequest.class);
    verify(activations).activate(request.capture());
    assertThat(request.getValue().targetNodeDefinitionId()).isEqualTo(target);
  }

  @Test
  void exclusiveRoutingUsesAscendingPriority() {
    EdgeDefinition firstFalse = edge(0, false, UUID.randomUUID(), falseExpression());
    EdgeDefinition secondTrue = edge(10, false, UUID.randomUUID(), trueExpression());
    EdgeDefinition fallback = edge(20, true, UUID.randomUUID(), null);
    outgoing().thenReturn(List.of(firstFalse, secondTrue, fallback));

    RoutingResult result = service.route(source.getId(), correlation(), command());

    assertThat(result.selectedEdgeIds()).containsExactly(secondTrue.getId());
  }

  @Test
  void exclusiveRoutingUsesDefaultOnlyWhenNoConditionMatches() {
    EdgeDefinition no = edge(0, false, UUID.randomUUID(), falseExpression());
    EdgeDefinition fallback = edge(1, true, UUID.randomUUID(), null);
    outgoing().thenReturn(List.of(no, fallback));

    assertThat(service.route(source.getId(), correlation(), command()).selectedEdgeIds())
        .containsExactly(fallback.getId());
  }

  @Test
  void throwsStableNoMatchErrorWhenRoutingIsRequired() {
    outgoing().thenReturn(List.of(edge(0, false, UUID.randomUUID(), falseExpression())));

    assertThatThrownBy(() -> service.route(source.getId(), correlation(), command()))
        .isInstanceOf(RoutingNoMatchException.class)
        .extracting(exception -> ((RoutingNoMatchException) exception).code())
        .isEqualTo("ROUTING_NO_MATCH");
  }

  @Test
  void duplicateRoutingReplaysThePersistedSelectionAndActivationIdentity() {
    EdgeDefinition selected = edge(0, false, UUID.randomUUID(), trueExpression());
    outgoing().thenReturn(List.of(selected));
    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    RoutingResult first = service.route(source.getId(), correlation(), command());
    verify(audits).save(saved.capture());
    when(audits.findAllByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(
            "NODE_EXECUTION", source.getId()))
        .thenReturn(List.of(saved.getValue()));

    RoutingResult replay = service.route(source.getId(), correlation(), command());

    assertThat(replay.selectedEdgeIds()).isEqualTo(first.selectedEdgeIds());
    ArgumentCaptor<ActivationRequest> requests = ArgumentCaptor.forClass(ActivationRequest.class);
    verify(activations, times(2)).activate(requests.capture());
    assertThat(requests.getAllValues().get(0).activationKey())
        .isEqualTo(requests.getAllValues().get(1).activationKey());
    verify(audits, times(1)).save(any());
  }

  @Test
  void allOutgoingActivatesEveryEdgeWithoutHandlerSelectedDestinations() {
    sourceNode = node(RoutingMode.ALL_OUTGOING);
    when(nodes.findById(sourceNodeId)).thenReturn(Optional.of(sourceNode));
    EdgeDefinition one = edge(0, false, UUID.randomUUID(), null);
    EdgeDefinition two = edge(1, false, UUID.randomUUID(), null);
    outgoing().thenReturn(List.of(one, two));

    assertThat(service.route(source.getId(), correlation(), command()).selectedEdgeIds())
        .containsExactly(one.getId(), two.getId());
    assertThat(NodeHandlerContext.class.getRecordComponents())
        .extracting(component -> component.getName())
        .doesNotContain("target", "targetNode", "nextNode", "routingService");
  }

  private NodeDefinition node(RoutingMode mode) {
    return NodeDefinition.create(
        sourceNodeId,
        versionId,
        "condition",
        "CONDITION",
        "Condition",
        null,
        1,
        mapper.createObjectNode().put("routingMode", mode.name()),
        null,
        null,
        mapper.createObjectNode().put("x", 0).put("y", 0));
  }

  private EdgeDefinition edge(
      int priority,
      boolean defaultEdge,
      UUID target,
      com.fpt.workflow.resolver.expression.Expression condition) {
    return EdgeDefinition.create(
        UUID.randomUUID(),
        versionId,
        sourceNodeId,
        "APPROVED",
        target,
        condition == null ? null : mapper.valueToTree(condition),
        priority,
        defaultEdge,
        TransitionType.CONDITIONAL,
        null,
        mapper.createObjectNode());
  }

  private org.mockito.stubbing.OngoingStubbing<List<EdgeDefinition>> outgoing() {
    return when(
        edges.findAllByWorkflowVersionIdAndSourceNodeIdAndSourcePortOrderByPriorityAscIdAsc(
            versionId, sourceNodeId, "APPROVED"));
  }

  private OperatorExpression trueExpression() {
    return OperatorExpression.of(
        ExpressionOperator.GTE,
        new ReferenceExpression("variables.score"),
        new LiteralExpression(JsonNodeFactory.instance.numberNode(5), INTEGER));
  }

  private OperatorExpression falseExpression() {
    return OperatorExpression.of(
        ExpressionOperator.LT,
        new ReferenceExpression("variables.score"),
        new LiteralExpression(JsonNodeFactory.instance.numberNode(5), INTEGER));
  }

  private CorrelationId correlation() {
    return new CorrelationId(UUID.randomUUID());
  }

  private CommandId command() {
    return new CommandId(UUID.randomUUID());
  }
}
