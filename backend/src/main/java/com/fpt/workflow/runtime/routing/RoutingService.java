package com.fpt.workflow.runtime.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.nodetype.NodeCapability;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.resolver.expression.Expression;
import com.fpt.workflow.resolver.expression.ExpressionEngine;
import com.fpt.workflow.resolver.expression.ExpressionScope;
import com.fpt.workflow.resolver.expression.NullPolicy;
import com.fpt.workflow.runtime.activation.ActivationKey;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.context.EventContextBuilder;
import com.fpt.workflow.runtime.context.RuntimeScope;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.domain.ActivationToken;
import com.fpt.workflow.runtime.routing.domain.RoutingDecision;
import com.fpt.workflow.runtime.routing.repository.ActivationTokenRepository;
import com.fpt.workflow.runtime.routing.repository.RoutingDecisionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Evaluates immutable edges after completion. Handlers and controllers never select targets. */
@Service
public class RoutingService {

  private static final Set<EventStatus> TERMINAL_EVENTS =
      EnumSet.of(
          EventStatus.COMPLETED, EventStatus.FAILED, EventStatus.CANCELLED, EventStatus.TERMINATED);

  private final NodeExecutionRepository executionRepository;
  private final EventRepository eventRepository;
  private final NodeDefinitionRepository nodeRepository;
  private final EdgeDefinitionRepository edgeRepository;
  private final EventContextBuilder contextBuilder;
  private final ExpressionEngine expressionEngine;
  private final NodeTypeRegistry registry;
  private final NodeActivationService activationService;
  private final RoutingDecisionRepository decisionRepository;
  private final ActivationTokenRepository tokenRepository;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;
  private final com.fpt.workflow.runtime.join.service.JoinService joinService;
  private final AuditEventRepository auditRepository;
  private final ActorContextProvider actorProvider;

  public RoutingService(
      NodeExecutionRepository executionRepository,
      EventRepository eventRepository,
      NodeDefinitionRepository nodeRepository,
      EdgeDefinitionRepository edgeRepository,
      EventContextBuilder contextBuilder,
      ExpressionEngine expressionEngine,
      NodeTypeRegistry registry,
      NodeActivationService activationService,
      RoutingDecisionRepository decisionRepository,
      ActivationTokenRepository tokenRepository,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper) {
    this(
        executionRepository,
        eventRepository,
        nodeRepository,
        edgeRepository,
        contextBuilder,
        expressionEngine,
        registry,
        activationService,
        decisionRepository,
        tokenRepository,
        uuidGenerator,
        clock,
        objectMapper,
        null,
        null,
        null);
  }

  public RoutingService(
      NodeExecutionRepository executionRepository,
      EventRepository eventRepository,
      NodeDefinitionRepository nodeRepository,
      EdgeDefinitionRepository edgeRepository,
      EventContextBuilder contextBuilder,
      ExpressionEngine expressionEngine,
      NodeTypeRegistry registry,
      NodeActivationService activationService,
      RoutingDecisionRepository decisionRepository,
      ActivationTokenRepository tokenRepository,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper,
      com.fpt.workflow.runtime.join.service.JoinService joinService) {
    this(
        executionRepository,
        eventRepository,
        nodeRepository,
        edgeRepository,
        contextBuilder,
        expressionEngine,
        registry,
        activationService,
        decisionRepository,
        tokenRepository,
        uuidGenerator,
        clock,
        objectMapper,
        joinService,
        null,
        null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public RoutingService(
      NodeExecutionRepository executionRepository,
      EventRepository eventRepository,
      NodeDefinitionRepository nodeRepository,
      EdgeDefinitionRepository edgeRepository,
      EventContextBuilder contextBuilder,
      ExpressionEngine expressionEngine,
      NodeTypeRegistry registry,
      NodeActivationService activationService,
      RoutingDecisionRepository decisionRepository,
      ActivationTokenRepository tokenRepository,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper,
      @org.springframework.context.annotation.Lazy
          com.fpt.workflow.runtime.join.service.JoinService joinService,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          AuditEventRepository auditRepository,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          ActorContextProvider actorProvider) {
    this.executionRepository = executionRepository;
    this.eventRepository = eventRepository;
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.contextBuilder = contextBuilder;
    this.expressionEngine = expressionEngine;
    this.registry = registry;
    this.activationService = activationService;
    this.decisionRepository = decisionRepository;
    this.tokenRepository = tokenRepository;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.joinService = joinService;
    this.auditRepository = auditRepository;
    this.actorProvider = actorProvider;
  }

  /**
   * Routes a completed node execution downstream. Idempotent: if a RoutingDecision already exists
   * for this source, replays from persisted ActivationTokens. Durable: tokens are written before
   * activation — crash-recovery retries PENDING tokens without re-evaluating conditions.
   */
  @Transactional
  public RoutingResult route(
      UUID sourceExecutionId, CorrelationId correlationId, CommandId commandId) {

    NodeExecution source =
        executionRepository
            .findByIdForUpdate(sourceExecutionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException("NodeExecution not found: " + sourceExecutionId));
    if (source.getStatus() != NodeExecutionStatus.COMPLETED) {
      throw new IllegalStateException("Routing requires a COMPLETED NodeExecution");
    }

    Event event =
        eventRepository
            .findById(source.getEventId())
            .orElseThrow(() -> new IllegalStateException("NodeExecution Event is missing"));
    NodeDefinition sourceNode =
        nodeRepository
            .findById(source.getNodeDefinitionId())
            .orElseThrow(() -> new IllegalStateException("NodeExecution definition is missing"));
    if (!sourceNode.getWorkflowVersionId().equals(event.getWorkflowVersionId())) {
      throw new IllegalStateException("Source node is outside the Event WorkflowVersion");
    }

    NodeTypeManifest manifest = registry.require(parseNodeType(sourceNode));
    RoutingMode mode = routingMode(sourceNode, manifest);

    // --- Crash-recovery / Idempotency: replay from persisted decision + tokens ---
    Optional<RoutingDecision> priorDecision =
        decisionRepository.findBySourceNodeExecutionId(source.getId());
    if (priorDecision.isPresent()) {
      return replayFromTokens(priorDecision.get(), mode, event, source, correlationId, commandId);
    }

    // --- First evaluation ---
    if (TERMINAL_EVENTS.contains(event.getStatus())) {
      throw new IllegalStateException("Cannot route a terminal Event");
    }

    List<EdgeDefinition> outgoing =
        edgeRepository
            .findAllByWorkflowVersionIdAndSourceNodeIdAndSourcePortOrderByPriorityAscIdAsc(
                event.getWorkflowVersionId(), sourceNode.getId(), source.getOutcomePort());

    RuntimeScope scope =
        RuntimeScope.occurrence(source.getCycleId(), source.getPathToken(), source.getItemToken());
    EventContext context = contextBuilder.build(event.getId(), scope);

    List<EdgeDefinition> selected = select(mode, manifest, outgoing, context);
    if (mode != RoutingMode.NONE && selected.isEmpty()) {
      throw new RoutingNoMatchException(
          "No route matched node " + sourceNode.getNodeKey() + " port " + source.getOutcomePort());
    }

    Instant now = clock.now();

    // Persist RoutingDecision first (explainability + replay anchor)
    ArrayNode evaluatedArray = JsonNodeFactory.instance.arrayNode();
    outgoing.forEach(e -> evaluatedArray.add(e.getId().toString()));
    ArrayNode selectedArray = JsonNodeFactory.instance.arrayNode();
    selected.forEach(e -> selectedArray.add(e.getId().toString()));

    RoutingDecision decision =
        decisionRepository.save(
            RoutingDecision.record(
                uuidGenerator.generate(),
                event.getId(),
                source.getId(),
                source.getOutcomePort() != null ? source.getOutcomePort() : "default",
                mode.name(),
                evaluatedArray,
                selectedArray,
                now));

    // Persist ActivationTokens before activating (at-least-once crash safety)
    List<ActivationToken> tokens = new ArrayList<>();
    UUID splitScopeId =
        (mode == RoutingMode.ALL_OUTGOING || selected.size() > 1)
            ? uuidGenerator.generate()
            : source.getSplitScopeId();
    UUID joinScopeId =
        (mode == RoutingMode.ALL_OUTGOING || selected.size() > 1)
            ? uuidGenerator.generate()
            : source.getJoinScopeId();

    for (EdgeDefinition edge : selected) {
      String path = childPath(source.getPathToken(), edge.getId());
      ActivationKey key =
          ActivationKey.downstream(
              event.getId(),
              source.getId(),
              edge.getId(),
              path,
              source.getCycleId(),
              source.getItemToken());

      ActivationToken token =
          tokenRepository.save(
              ActivationToken.pending(
                  uuidGenerator.generate(),
                  decision.getId(),
                  event.getId(),
                  source.getId(),
                  edge.getId(),
                  edge.getTargetNodeId(),
                  key.value(),
                  path,
                  source.getCycleId(),
                  source.getItemToken(),
                  splitScopeId,
                  joinScopeId,
                  now));
      tokens.add(token);
    }

    // Activate tokens (idempotency guaranteed by activation_key UNIQUE on node_executions)
    RoutingResult result = activateTokens(tokens, event, source, mode, correlationId, commandId);
    recordAudit(event, source, result, correlationId, commandId);
    return result;
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // Crash-recovery: replay from persisted decision + existing tokens
  // ──────────────────────────────────────────────────────────────────────────────

  private RoutingResult replayFromTokens(
      RoutingDecision decision,
      RoutingMode mode,
      Event event,
      NodeExecution source,
      CorrelationId correlationId,
      CommandId commandId) {

    List<ActivationToken> allTokens = tokenRepository.findAllByRoutingDecisionId(decision.getId());
    return activateTokens(allTokens, event, source, mode, correlationId, commandId);
  }

  private RoutingResult activateTokens(
      List<ActivationToken> tokens,
      Event event,
      NodeExecution source,
      RoutingMode mode,
      CorrelationId correlationId,
      CommandId commandId) {

    Instant now = clock.now();
    List<NodeExecution> downstream = new ArrayList<>();
    List<UUID> selectedEdgeIds = new ArrayList<>();

    for (ActivationToken token : tokens) {
      if (joinService != null) {
        Optional<NodeDefinition> targetNodeOpt =
            nodeRepository.findById(token.getTargetNodeDefinitionId());
        if (targetNodeOpt.isPresent() && joinService.isJoin(targetNodeOpt.get())) {
          NodeExecution je =
              joinService.arrive(
                  event,
                  targetNodeOpt.get(),
                  source,
                  token.getEdgeId(),
                  token.getJoinScopeId(),
                  correlationId,
                  commandId);
          downstream.add(je);
          selectedEdgeIds.add(token.getEdgeId());
          if (token.isPending()) {
            token.markActivated(now);
            tokenRepository.save(token);
          }
          continue;
        }
      }

      ActivationKey key = new ActivationKey(token.getActivationKey());
      NodeExecution ne =
          activationService.activate(
              new ActivationRequest(
                  token.getEventId(),
                  token.getTargetNodeDefinitionId(),
                  key,
                  token.getCycleId(),
                  source.getIteration(),
                  token.getPathToken(),
                  token.getItemToken(),
                  token.getSplitScopeId(),
                  token.getJoinScopeId(),
                  correlationId,
                  commandId));
      downstream.add(ne);
      selectedEdgeIds.add(token.getEdgeId());

      if (token.isPending()) {
        token.markActivated(now);
        tokenRepository.save(token);
      }
    }

    return new RoutingResult(mode, selectedEdgeIds, downstream);
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // Selection logic
  // ──────────────────────────────────────────────────────────────────────────────

  private List<EdgeDefinition> select(
      RoutingMode mode,
      NodeTypeManifest manifest,
      List<EdgeDefinition> outgoing,
      EventContext context) {
    return switch (mode) {
      case NONE -> {
        if (!outgoing.isEmpty()) {
          throw new IllegalStateException("NONE routing mode cannot have outgoing edges");
        }
        yield List.of();
      }
      case ALL_OUTGOING -> List.copyOf(outgoing);
      case SINGLE_BY_PORT -> selectSingle(outgoing, context);
      case EXCLUSIVE_CONDITIONAL -> selectExclusive(outgoing, context);
      case ALL_MATCHING -> {
        if (!manifest.supportedCapabilities().contains(NodeCapability.ALL_MATCHING_ROUTING)) {
          throw new IllegalStateException("Node type does not support ALL_MATCHING routing");
        }
        yield selectAllMatching(outgoing, context);
      }
    };
  }

  private List<EdgeDefinition> selectSingle(List<EdgeDefinition> outgoing, EventContext context) {
    if (outgoing.size() > 1) {
      throw new IllegalStateException(
          "SINGLE_BY_PORT requires at most one edge for the outcome port");
    }
    if (outgoing.isEmpty()) return List.of();
    EdgeDefinition edge = outgoing.getFirst();
    return edge.isDefaultTransition() || matches(edge, context) ? List.of(edge) : List.of();
  }

  private List<EdgeDefinition> selectExclusive(
      List<EdgeDefinition> outgoing, EventContext context) {
    EdgeDefinition fallback = null;
    for (EdgeDefinition edge : outgoing) {
      if (edge.isDefaultTransition()) {
        fallback = edge;
      } else if (matches(edge, context)) {
        return List.of(edge);
      }
    }
    return fallback == null ? List.of() : List.of(fallback);
  }

  private List<EdgeDefinition> selectAllMatching(
      List<EdgeDefinition> outgoing, EventContext context) {
    List<EdgeDefinition> matches = new ArrayList<>();
    EdgeDefinition fallback = null;
    for (EdgeDefinition edge : outgoing) {
      if (edge.isDefaultTransition()) fallback = edge;
      else if (matches(edge, context)) matches.add(edge);
    }
    if (matches.isEmpty() && fallback != null) matches.add(fallback);
    return List.copyOf(matches);
  }

  private boolean matches(EdgeDefinition edge, EventContext context) {
    if (edge.getConditionJson() == null) return true;
    Expression expression = objectMapper.convertValue(edge.getConditionJson(), Expression.class);
    var compiled =
        expressionEngine.compile(expression, ExpressionScope.RUNTIME, context.expressionSchema());
    if (compiled.resultType().type() != CanonicalValueType.BOOLEAN
        || compiled.resultType().isCollection()) {
      throw new IllegalStateException("Edge condition must produce BOOLEAN");
    }
    return expressionEngine
        .evaluate(compiled, context.value(), NullPolicy.NULL_IS_FALSE)
        .booleanValue();
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // Utilities
  // ──────────────────────────────────────────────────────────────────────────────

  private RoutingMode routingMode(NodeDefinition node, NodeTypeManifest manifest) {
    String configured = node.getConfigJson().path("routingMode").asText(null);
    if (configured != null) {
      try {
        return RoutingMode.valueOf(configured);
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException("Unsupported routingMode: " + configured, exception);
      }
    }
    if (manifest.supportedCapabilities().contains(NodeCapability.TERMINAL)) return RoutingMode.NONE;
    if (manifest.supportedCapabilities().contains(NodeCapability.ROUTING)) {
      return RoutingMode.EXCLUSIVE_CONDITIONAL;
    }
    return RoutingMode.SINGLE_BY_PORT;
  }

  private String childPath(String parent, UUID edgeId) {
    String segment = edgeId.toString().substring(0, 8);
    String path = parent + "/" + segment;
    if (path.length() > 256) {
      throw new IllegalStateException("Runtime path scope exceeds supported depth");
    }
    return path;
  }

  private void recordAudit(
      Event event,
      NodeExecution source,
      RoutingResult result,
      CorrelationId correlationId,
      CommandId commandId) {
    if (auditRepository == null) return;
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("eventId", event.getId().toString());
    metadata.put("outcomePort", source.getOutcomePort());
    metadata.put("routingMode", result.mode().name());
    ArrayNode selected = metadata.putArray("selectedEdgeIds");
    result.selectedEdgeIds().forEach(id -> selected.add(id.toString()));
    Optional<ActorContext> actor =
        actorProvider != null ? actorProvider.currentActor() : Optional.empty();
    UUID actorId = actor.map(ActorContext::actorId).orElse(event.getStartedBy());
    auditRepository.save(
        AuditEvent.record(
            uuidGenerator.generate(),
            "NODE_EXECUTION",
            source.getId(),
            "NODE_ROUTED",
            actorId,
            actorId,
            correlationId,
            commandId,
            metadata,
            clock.now()));
  }

  private NodeType parseNodeType(NodeDefinition node) {
    try {
      return NodeType.valueOf(node.getNodeType());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Published node type is not registered", exception);
    }
  }
}
