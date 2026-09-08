package com.fpt.workflow.runtime.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.nodetype.NodeExecutionResult;
import com.fpt.workflow.nodetype.NodeHandler;
import com.fpt.workflow.nodetype.NodeRuntimeServices;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeProvider;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.nodetype.StrictNodeValidator;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.resolver.expression.SafeExpressionEngine;
import com.fpt.workflow.runtime.binding.EventVariableMapper;
import com.fpt.workflow.runtime.binding.InputBindingResolver;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeActivationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final EventRepository events = mock(EventRepository.class);
  private final NodeExecutionRepository executions = mock(NodeExecutionRepository.class);
  private final NodeDefinitionRepository nodes = mock(NodeDefinitionRepository.class);
  private final WorkflowVariableRepository variables = mock(WorkflowVariableRepository.class);
  private final EventContextBuilder contexts = mock(EventContextBuilder.class);
  private final ParticipantActivationHook participants = mock(ParticipantActivationHook.class);
  private final AuditEventRepository audits = mock(AuditEventRepository.class);
  private final ActorContextProvider actors = mock(ActorContextProvider.class);
  private final NodeHandler handler = mock(NodeHandler.class);
  private final AtomicReference<NodeExecution> lastSaved = new AtomicReference<>();
  private NodeActivationService service;
  private Event event;
  private NodeDefinition node;
  private UUID eventId;
  private UUID nodeId;
  private UUID cycleId;

  @BeforeEach
  void setUp() {
    eventId = UUID.randomUUID();
    nodeId = UUID.randomUUID();
    cycleId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    event =
        Event.createRoot(
            eventId,
            UUID.randomUUID(),
            versionId,
            UUID.randomUUID(),
            null,
            null,
            "TEST",
            "activation",
            mapper.createObjectNode(),
            UUID.randomUUID(),
            NOW);
    node =
        NodeDefinition.create(
            nodeId,
            versionId,
            "start",
            "START",
            "Start",
            null,
            1,
            mapper.createObjectNode(),
            null,
            null,
            mapper.createObjectNode().put("x", 0).put("y", 0));
    CanonicalSchema empty = CanonicalSchema.strict(Map.of(), Set.of());
    when(handler.supports()).thenReturn(NodeType.START);
    when(handler.execute(any()))
        .thenReturn(NodeExecutionResult.complete(mapper.createObjectNode(), "DONE"));
    NodeTypeManifest manifest =
        new NodeTypeManifest(
            NodeType.START,
            1,
            Set.of(com.fpt.workflow.nodetype.NodeCapability.ENTRY),
            empty,
            empty,
            Set.of("DONE"),
            empty,
            mapper.createObjectNode(),
            new StrictNodeValidator(empty),
            handler);
    NodeTypeRegistry registry = new NodeTypeRegistry(List.of((NodeTypeProvider) () -> manifest));
    EventContext context = mock(EventContext.class);
    when(contexts.build(any(UUID.class), any())).thenReturn(context);
    when(executions.saveAndFlush(any()))
        .thenAnswer(
            invocation -> {
              NodeExecution value = invocation.getArgument(0);
              lastSaved.set(value);
              return value;
            });
    when(executions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(actors.currentActor()).thenReturn(Optional.empty());
    NodeRuntimeServices runtimeServices =
        new NodeRuntimeServices() {
          @Override
          public Instant now() {
            return NOW;
          }

          @Override
          public UUID newId() {
            return UUID.randomUUID();
          }
        };
    service =
        new NodeActivationService(
            events,
            executions,
            nodes,
            variables,
            contexts,
            new InputBindingResolver(new SafeExpressionEngine()),
            new EventVariableMapper(new SafeExpressionEngine()),
            registry,
            participants,
            runtimeServices,
            audits,
            actors,
            UUID::randomUUID,
            () -> NOW,
            mapper);
    when(events.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
    when(nodes.findById(nodeId)).thenReturn(Optional.of(node));
    when(executions.findByActivationKey(any())).thenReturn(Optional.empty());
  }

  @Test
  void duplicateActivationReturnsTheOriginalOccurrenceAndDoesNotRedispatch() {
    ActivationRequest request = request(ActivationKey.root(eventId, nodeId));
    NodeExecution first = service.activate(request);
    when(executions.findByActivationKey(request.activationKey().value()))
        .thenReturn(Optional.of(first));

    NodeExecution replay = service.activate(request);

    assertThat(replay).isSameAs(first);
    verify(handler, times(1)).execute(any());
    verify(audits, times(1)).save(any());
  }

  @Test
  void sameNodeCanCreateASecondLegitimateOccurrenceWithAnotherActivationIdentity() {
    NodeExecution first = service.activate(request(new ActivationKey("test:first")));
    NodeExecution second = service.activate(request(new ActivationKey("test:second")));

    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(second.getNodeDefinitionId()).isEqualTo(first.getNodeDefinitionId());
    verify(handler, times(2)).execute(any());
  }

  @Test
  void rejectsNewActivationForTerminalEvent() {
    event.markRunning();
    event.complete("DONE", NOW);

    assertThatThrownBy(() -> service.activate(request(new ActivationKey("test:terminal"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal Event");
    verify(handler, never()).execute(any());
  }

  @Test
  void rejectsTargetFromAnotherWorkflowVersion() {
    NodeDefinition foreign =
        NodeDefinition.create(
            nodeId,
            UUID.randomUUID(),
            "foreign",
            "START",
            "Foreign",
            null,
            1,
            mapper.createObjectNode(),
            null,
            null,
            mapper.createObjectNode().put("x", 0).put("y", 0));
    when(nodes.findById(nodeId)).thenReturn(Optional.of(foreign));

    assertThatThrownBy(() -> service.activate(request(new ActivationKey("test:foreign"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WorkflowVersion");
    verify(handler, never()).execute(any());
  }

  @Test
  void rootActivationIdentityIsStable() {
    assertThat(ActivationKey.root(eventId, nodeId))
        .isEqualTo(ActivationKey.root(eventId, nodeId))
        .isNotEqualTo(ActivationKey.root(UUID.randomUUID(), nodeId));
  }

  private ActivationRequest request(ActivationKey key) {
    return new ActivationRequest(
        eventId,
        nodeId,
        key,
        cycleId,
        0,
        "root",
        null,
        null,
        null,
        new CorrelationId(UUID.randomUUID()),
        new CommandId(UUID.randomUUID()));
  }
}
