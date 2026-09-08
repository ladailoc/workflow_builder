package com.fpt.workflow.runtime.activation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.nodetype.NodeCapability;
import com.fpt.workflow.nodetype.NodeExecutionError;
import com.fpt.workflow.nodetype.NodeExecutionResult;
import com.fpt.workflow.nodetype.NodeHandlerContext;
import com.fpt.workflow.nodetype.NodeRuntimeServices;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.binding.EventVariableMapper;
import com.fpt.workflow.runtime.binding.InputBinding;
import com.fpt.workflow.runtime.binding.InputBindingResolver;
import com.fpt.workflow.runtime.binding.VariableMapping;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.context.EventContextBuilder;
import com.fpt.workflow.runtime.context.RuntimeScope;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.value.CanonicalSchema;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates one immutable node occurrence for one activation identity and dispatches its handler. */
@Service
public class NodeActivationService {

  private static final Set<EventStatus> TERMINAL_EVENTS =
      EnumSet.of(
          EventStatus.COMPLETED, EventStatus.FAILED, EventStatus.CANCELLED, EventStatus.TERMINATED);
  private static final TypeReference<List<InputBinding>> INPUT_BINDINGS = new TypeReference<>() {};
  private static final TypeReference<List<VariableMapping>> VARIABLE_MAPPINGS =
      new TypeReference<>() {};

  private final EventRepository eventRepository;
  private final NodeExecutionRepository executionRepository;
  private final NodeDefinitionRepository nodeRepository;
  private final WorkflowVariableRepository variableRepository;
  private final EventContextBuilder contextBuilder;
  private final InputBindingResolver bindingResolver;
  private final EventVariableMapper variableMapper;
  private final NodeTypeRegistry registry;
  private final ParticipantActivationHook participantHook;
  private final NodeRuntimeServices runtimeServices;
  private final AuditEventRepository auditRepository;
  private final ActorContextProvider actorProvider;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;
  private final com.fpt.workflow.runtime.multiinstance.service.MultiInstanceService
      multiInstanceService;
  private final com.fpt.workflow.runtime.subworkflow.service.SubWorkflowService subWorkflowService;

  public NodeActivationService(
      EventRepository eventRepository,
      NodeExecutionRepository executionRepository,
      NodeDefinitionRepository nodeRepository,
      WorkflowVariableRepository variableRepository,
      EventContextBuilder contextBuilder,
      InputBindingResolver bindingResolver,
      EventVariableMapper variableMapper,
      NodeTypeRegistry registry,
      ParticipantActivationHook participantHook,
      NodeRuntimeServices runtimeServices,
      AuditEventRepository auditRepository,
      ActorContextProvider actorProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper) {
    this(
        eventRepository,
        executionRepository,
        nodeRepository,
        variableRepository,
        contextBuilder,
        bindingResolver,
        variableMapper,
        registry,
        participantHook,
        runtimeServices,
        auditRepository,
        actorProvider,
        uuidGenerator,
        clock,
        objectMapper,
        null,
        null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public NodeActivationService(
      EventRepository eventRepository,
      NodeExecutionRepository executionRepository,
      NodeDefinitionRepository nodeRepository,
      WorkflowVariableRepository variableRepository,
      EventContextBuilder contextBuilder,
      InputBindingResolver bindingResolver,
      EventVariableMapper variableMapper,
      NodeTypeRegistry registry,
      ParticipantActivationHook participantHook,
      NodeRuntimeServices runtimeServices,
      AuditEventRepository auditRepository,
      ActorContextProvider actorProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper,
      @org.springframework.context.annotation.Lazy
          com.fpt.workflow.runtime.multiinstance.service.MultiInstanceService multiInstanceService,
      @org.springframework.context.annotation.Lazy
          com.fpt.workflow.runtime.subworkflow.service.SubWorkflowService subWorkflowService) {
    this.eventRepository = eventRepository;
    this.executionRepository = executionRepository;
    this.nodeRepository = nodeRepository;
    this.variableRepository = variableRepository;
    this.contextBuilder = contextBuilder;
    this.bindingResolver = bindingResolver;
    this.variableMapper = variableMapper;
    this.registry = registry;
    this.participantHook = participantHook;
    this.runtimeServices = runtimeServices;
    this.auditRepository = auditRepository;
    this.actorProvider = actorProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.multiInstanceService = multiInstanceService;
    this.subWorkflowService = subWorkflowService;
  }

  @Transactional
  public NodeExecution activate(ActivationRequest request) {
    Event event =
        eventRepository
            .findByIdForUpdate(request.eventId())
            .orElseThrow(
                () -> new IllegalArgumentException("Event not found: " + request.eventId()));
    Optional<NodeExecution> replay =
        executionRepository.findByActivationKey(request.activationKey().value());
    if (replay.isPresent()) {
      requireSameActivation(replay.orElseThrow(), request);
      return replay.orElseThrow();
    }
    if (TERMINAL_EVENTS.contains(event.getStatus())) {
      throw new IllegalStateException("Cannot activate a node for terminal Event " + event.getId());
    }
    NodeDefinition node =
        nodeRepository
            .findById(request.targetNodeDefinitionId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "NodeDefinition not found: " + request.targetNodeDefinitionId()));
    if (!node.getWorkflowVersionId().equals(event.getWorkflowVersionId())) {
      throw new IllegalArgumentException("Target node does not belong to Event WorkflowVersion");
    }
    NodeTypeManifest manifest = registry.require(parseNodeType(node));
    RuntimeScope scope =
        RuntimeScope.occurrence(request.cycleId(), request.pathToken(), request.itemToken());
    EventContext beforeActivation = contextBuilder.build(event.getId(), scope);
    ObjectNode input =
        bindingResolver.resolve(decodeInputs(node.getConfigJson()), beforeActivation);
    Instant now = clock.now();
    NodeExecution execution =
        NodeExecution.create(
            uuidGenerator.generate(),
            event.getId(),
            node.getId(),
            request.activationKey().value(),
            request.cycleId(),
            request.iteration(),
            request.pathToken(),
            request.itemToken(),
            request.splitScopeId(),
            request.joinScopeId(),
            input,
            event.getStartedTicketRevisionId(),
            now);
    execution.markReady();
    execution.start(now);
    execution = executionRepository.saveAndFlush(execution);
    if (event.getStatus() != EventStatus.RUNNING) event.markRunning();
    if (multiInstanceService != null && multiInstanceService.isMultiInstance(node)) {
      com.fpt.workflow.runtime.multiinstance.domain.MultiInstanceState miState =
          multiInstanceService.initialize(
              event,
              execution,
              node,
              beforeActivation,
              request.correlationId(),
              request.commandId());
      if (manifest.supportedCapabilities().contains(NodeCapability.PARTICIPANT)) {
        participantHook.onActivation(event, node, execution, beforeActivation);
      }
      if ("COMPLETED".equals(miState.getStatus())) {
        return execution;
      }
      execution.waitFor(RuntimeWaitReason.MULTI_INSTANCE);
      event.waitFor(RuntimeWaitReason.MULTI_INSTANCE);
      eventRepository.save(event);
      execution = executionRepository.saveAndFlush(execution);
      recordAudit(
          event,
          node,
          execution,
          NodeExecutionResult.waitFor(
              new com.fpt.workflow.nodetype.WaitDescriptor(
                  RuntimeWaitReason.MULTI_INSTANCE.name(),
                  execution.getId().toString(),
                  objectMapper.createObjectNode())),
          request,
          now);
      return execution;
    }

    if (manifest.supportedCapabilities().contains(NodeCapability.PARTICIPANT)) {
      participantHook.onActivation(event, node, execution, beforeActivation);
    }

    NodeExecutionResult result;
    if (subWorkflowService != null && subWorkflowService.isSubWorkflowNode(node)) {
      result =
          subWorkflowService.activateSubWorkflow(event, node, execution, input, request, scope);
    } else {
      result =
          manifest
              .handler()
              .execute(
                  new NodeHandlerContext(
                      execution.getId(),
                      node.getNodeKey(),
                      input,
                      node.getConfigJson(),
                      runtimeServices));
    }
    applyResult(event, node, manifest, execution, result, request, scope);
    eventRepository.save(event);
    execution = executionRepository.saveAndFlush(execution);
    recordAudit(event, node, execution, result, request, now);
    return execution;
  }

  @Transactional
  public NodeExecution activateRoot(
      UUID eventId,
      UUID cycleId,
      com.fpt.workflow.shared.domain.CorrelationId correlationId,
      com.fpt.workflow.shared.domain.CommandId commandId) {
    Event event =
        eventRepository
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    List<NodeDefinition> entries =
        nodeRepository
            .findAllByWorkflowVersionIdOrderByNodeKeyAsc(event.getWorkflowVersionId())
            .stream()
            .filter(
                node ->
                    registry
                        .require(parseNodeType(node))
                        .supportedCapabilities()
                        .contains(NodeCapability.ENTRY))
            .toList();
    if (entries.size() != 1) {
      throw new IllegalStateException("Published WorkflowVersion must have exactly one entry node");
    }
    return activate(
        ActivationRequest.root(
            eventId, entries.getFirst().getId(), cycleId, correlationId, commandId));
  }

  private void applyResult(
      Event event,
      NodeDefinition node,
      NodeTypeManifest manifest,
      NodeExecution execution,
      NodeExecutionResult result,
      ActivationRequest request,
      RuntimeScope scope) {
    Instant endedAt = clock.now();
    if (result instanceof NodeExecutionResult.Complete complete) {
      if (!manifest.outputPorts().contains(complete.outcomePort())) {
        throw new IllegalStateException("Handler returned an undeclared output port");
      }
      CanonicalValueValidator.validate(effectiveOutputSchema(node, manifest), complete.output())
          .requireValid();
      execution.complete(complete.outcomePort(), complete.output(), endedAt);
      List<VariableMapping> mappings = decodeMappings(node.getConfigJson());
      if (!mappings.isEmpty()) {
        executionRepository.saveAndFlush(execution);
        EventContext afterOutput = contextBuilder.build(event.getId(), scope);
        List<WorkflowVariable> declarations =
            variableRepository.findAllByWorkflowVersionIdOrderByKeyAsc(
                event.getWorkflowVersionId());
        variableMapper.apply(event, declarations, mappings, afterOutput);
      }
      return;
    }
    if (result instanceof NodeExecutionResult.Wait wait) {
      RuntimeWaitReason reason;
      try {
        reason = RuntimeWaitReason.valueOf(wait.descriptor().waitType());
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException("Handler returned unsupported wait type", exception);
      }
      execution.waitFor(reason);
      event.waitFor(reason);
      return;
    }
    NodeExecutionError error = ((NodeExecutionResult.Fail) result).error();
    ObjectNode errorJson = JsonNodeFactory.instance.objectNode();
    errorJson.put("code", error.code());
    errorJson.put("message", error.message());
    errorJson.set("details", error.details());
    execution.fail(errorJson, endedAt);
    event.fail(endedAt);
  }

  private CanonicalSchema effectiveOutputSchema(NodeDefinition node, NodeTypeManifest manifest) {
    if (node.getOutputSchemaJson() == null) return manifest.outputSchema();
    return objectMapper.convertValue(node.getOutputSchemaJson(), CanonicalSchema.class);
  }

  private List<InputBinding> decodeInputs(JsonNode config) {
    JsonNode value = config.get("inputBindings");
    return value == null
        ? List.of()
        : List.copyOf(objectMapper.convertValue(value, INPUT_BINDINGS));
  }

  private List<VariableMapping> decodeMappings(JsonNode config) {
    JsonNode value = config.get("variableMappings");
    return value == null
        ? List.of()
        : List.copyOf(objectMapper.convertValue(value, VARIABLE_MAPPINGS));
  }

  private NodeType parseNodeType(NodeDefinition node) {
    try {
      return NodeType.valueOf(node.getNodeType());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Published node type is not registered: " + node.getNodeType(), exception);
    }
  }

  private void requireSameActivation(NodeExecution existing, ActivationRequest request) {
    if (!existing.getEventId().equals(request.eventId())
        || !existing.getNodeDefinitionId().equals(request.targetNodeDefinitionId())
        || !existing.getCycleId().equals(request.cycleId())
        || !existing.getPathToken().equals(request.pathToken())
        || !java.util.Objects.equals(existing.getItemToken(), request.itemToken())) {
      throw new IllegalStateException("Activation key was reused with different semantics");
    }
  }

  private void recordAudit(
      Event event,
      NodeDefinition node,
      NodeExecution execution,
      NodeExecutionResult result,
      ActivationRequest request,
      Instant occurredAt) {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("eventId", event.getId().toString());
    metadata.put("nodeDefinitionId", node.getId().toString());
    metadata.put("nodeKey", node.getNodeKey());
    metadata.put("activationKey", execution.getActivationKey());
    metadata.put("result", result.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT));
    Optional<ActorContext> actor = actorProvider.currentActor();
    UUID actorId = actor.map(ActorContext::actorId).orElse(event.getStartedBy());
    auditRepository.save(
        AuditEvent.record(
            uuidGenerator.generate(),
            "NODE_EXECUTION",
            execution.getId(),
            "NODE_ACTIVATED",
            actorId,
            actorId,
            request.correlationId(),
            request.commandId(),
            metadata,
            occurredAt));
  }
}
