package com.fpt.workflow.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.nodetype.NodeCapability;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import com.fpt.workflow.organization.service.ManagerNotFoundException;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.participant.CreatorParticipantResolver;
import com.fpt.workflow.resolver.participant.ExpressionParticipantResolver;
import com.fpt.workflow.resolver.participant.FixedUserParticipantResolver;
import com.fpt.workflow.resolver.participant.GroupMembersParticipantResolver;
import com.fpt.workflow.resolver.participant.HeadOfUnitParticipantResolver;
import com.fpt.workflow.resolver.participant.ItemManagerParticipantResolver;
import com.fpt.workflow.resolver.participant.ItemUserParticipantResolver;
import com.fpt.workflow.resolver.participant.ManagerOfParticipantResolver;
import com.fpt.workflow.resolver.participant.NodeOutputParticipantResolver;
import com.fpt.workflow.resolver.participant.ParticipantResolutionEngine;
import com.fpt.workflow.resolver.participant.ParticipantResolutionException;
import com.fpt.workflow.resolver.participant.ParticipantResolverContext;
import com.fpt.workflow.resolver.participant.ParticipantResolverRegistry;
import com.fpt.workflow.resolver.participant.PreviousParticipantResolver;
import com.fpt.workflow.resolver.participant.RequestFieldParticipantResolver;
import com.fpt.workflow.resolver.participant.RoleMembersParticipantResolver;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.activation.ParticipantActivationHook;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.multiinstance.domain.CompletionPolicy;
import com.fpt.workflow.runtime.multiinstance.domain.NodeItemExecution;
import com.fpt.workflow.runtime.multiinstance.repository.NodeItemExecutionRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.aggregation.DecisionAggregationPolicy;
import com.fpt.workflow.task.aggregation.RejectBehavior;
import com.fpt.workflow.task.aggregation.RemainingTaskBehavior;
import com.fpt.workflow.task.aggregation.TaskAggregationPolicy;
import com.fpt.workflow.task.aggregation.TaskAggregationState;
import com.fpt.workflow.task.aggregation.TaskAggregationStateRepository;
import com.fpt.workflow.task.domain.TaskCandidate;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskCandidateRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** Resolves participant and instantiates a human task occurrence lazily on node activation. */
@Service
@Primary
public class HumanTaskParticipantActivationHook implements ParticipantActivationHook {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(HumanTaskParticipantActivationHook.class);

  private final TaskExecutionRepository taskRepository;
  private final ParticipantSnapshotRepository snapshotRepository;
  private final NodeTypeRegistry registry;
  private final OrganizationHierarchyService hierarchyService;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;
  private final TaskSlaActivationPort slaActivationService;
  private final NodeItemExecutionRepository itemRepository;
  private final ParticipantResolverRegistry participantRegistry;
  private final TaskAggregationStateRepository aggregationStateRepository;
  private final TaskCandidateRepository candidateRepository;
  private final ParticipantResolutionEngine resolutionEngine;
  private final WorkflowVersionRepository workflowVersions;
  private final WorkflowDefinitionRepository workflowDefinitions;

  @Autowired
  public HumanTaskParticipantActivationHook(
      TaskExecutionRepository taskRepository,
      ParticipantSnapshotRepository snapshotRepository,
      NodeTypeRegistry registry,
      OrganizationHierarchyService hierarchyService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper,
      TaskSlaActivationPort slaActivationService,
      NodeItemExecutionRepository itemRepository,
      ParticipantResolverRegistry participantRegistry,
      TaskAggregationStateRepository aggregationStateRepository,
      TaskCandidateRepository candidateRepository,
      @Autowired(required = false) ParticipantResolutionEngine resolutionEngine,
      WorkflowVersionRepository workflowVersions,
      WorkflowDefinitionRepository workflowDefinitions) {
    this.taskRepository = taskRepository;
    this.snapshotRepository = snapshotRepository;
    this.registry = registry;
    this.hierarchyService = hierarchyService;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.slaActivationService = slaActivationService;
    this.itemRepository = itemRepository;
    this.participantRegistry = participantRegistry;
    this.aggregationStateRepository = aggregationStateRepository;
    this.candidateRepository = candidateRepository;
    this.resolutionEngine =
        resolutionEngine != null
            ? resolutionEngine
            : new ParticipantResolutionEngine(
                participantRegistry != null ? participantRegistry : defaultRegistry(hierarchyService),
                null);
    this.workflowVersions = workflowVersions;
    this.workflowDefinitions = workflowDefinitions;
  }

  public HumanTaskParticipantActivationHook(
      TaskExecutionRepository taskRepository,
      ParticipantSnapshotRepository snapshotRepository,
      NodeTypeRegistry registry,
      OrganizationHierarchyService hierarchyService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper,
      TaskSlaActivationPort slaActivationService,
      NodeItemExecutionRepository itemRepository,
      ParticipantResolverRegistry participantRegistry,
      TaskAggregationStateRepository aggregationStateRepository,
      TaskCandidateRepository candidateRepository,
      ParticipantResolutionEngine resolutionEngine) {
    this(
        taskRepository,
        snapshotRepository,
        registry,
        hierarchyService,
        uuidGenerator,
        clock,
        objectMapper,
        slaActivationService,
        itemRepository,
        participantRegistry,
        aggregationStateRepository,
        candidateRepository,
        resolutionEngine,
        null,
        null);
  }

  public HumanTaskParticipantActivationHook(
      TaskExecutionRepository taskRepository,
      ParticipantSnapshotRepository snapshotRepository,
      NodeTypeRegistry registry,
      OrganizationHierarchyService hierarchyService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper,
      TaskSlaActivationPort slaActivationService,
      NodeItemExecutionRepository itemRepository,
      ParticipantResolverRegistry participantRegistry,
      TaskAggregationStateRepository aggregationStateRepository,
      TaskCandidateRepository candidateRepository) {
    this(
        taskRepository,
        snapshotRepository,
        registry,
        hierarchyService,
        uuidGenerator,
        clock,
        objectMapper,
        slaActivationService,
        itemRepository,
        participantRegistry,
        aggregationStateRepository,
        candidateRepository,
        null);
  }

  public HumanTaskParticipantActivationHook(
      TaskExecutionRepository taskRepository,
      ParticipantSnapshotRepository snapshotRepository,
      NodeTypeRegistry registry,
      OrganizationHierarchyService hierarchyService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper,
      TaskSlaActivationPort slaActivationService,
      NodeItemExecutionRepository itemRepository,
      ParticipantResolverRegistry participantRegistry) {
    this(
        taskRepository,
        snapshotRepository,
        registry,
        hierarchyService,
        uuidGenerator,
        clock,
        objectMapper,
        slaActivationService,
        itemRepository,
        participantRegistry,
        null,
        null,
        null);
  }

  public HumanTaskParticipantActivationHook(
      TaskExecutionRepository taskRepository,
      ParticipantSnapshotRepository snapshotRepository,
      NodeTypeRegistry registry,
      OrganizationHierarchyService hierarchyService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper,
      TaskSlaActivationPort slaActivationService,
      NodeItemExecutionRepository itemRepository) {
    this(
        taskRepository,
        snapshotRepository,
        registry,
        hierarchyService,
        uuidGenerator,
        clock,
        objectMapper,
        slaActivationService,
        itemRepository,
        defaultRegistry(hierarchyService),
        null,
        null,
        null);
  }

  private static ParticipantResolverRegistry defaultRegistry(
      OrganizationHierarchyService hierarchyService) {
    return new ParticipantResolverRegistry(
        List.of(
            new FixedUserParticipantResolver(),
            new CreatorParticipantResolver(),
            new ManagerOfParticipantResolver(hierarchyService),
            new ItemUserParticipantResolver(),
            new ItemManagerParticipantResolver(hierarchyService),
            new HeadOfUnitParticipantResolver(hierarchyService),
            new RequestFieldParticipantResolver(),
            new RoleMembersParticipantResolver(),
            new GroupMembersParticipantResolver(),
            new PreviousParticipantResolver(),
            new NodeOutputParticipantResolver(),
            new ExpressionParticipantResolver()));
  }

  @Override
  public void onActivation(
      Event event, NodeDefinition node, NodeExecution execution, EventContext eventContext) {
    NodeType type = NodeType.valueOf(node.getNodeType());
    NodeTypeManifest manifest = registry.require(type);
    if (!manifest.supportedCapabilities().contains(NodeCapability.HUMAN_TASK)) {
      return;
    }

    if (node.getConfigJson().hasNonNull("multiInstance")) {
      JsonNode miConfig = node.getConfigJson().get("multiInstance");
      String itemVariable = miConfig.path("itemVariable").asText("item");
      for (NodeItemExecution item :
          itemRepository.findAllByParentNodeExecutionIdOrderByItemIndexAsc(execution.getId())) {
        EventContext itemContext =
            eventContext != null
                ? eventContext.withItemVariable(itemVariable, item.getItemDataJson())
                : null;
        createTask(event, node, execution, item, item.getItemDataJson(), null, itemContext);
      }
      return;
    }

    JsonNode config = node.getConfigJson();
    JsonNode participantNode = config.path("participant");
    JsonNode taskNode = config.path("task");

    String strategy =
        taskNode
            .path("generationStrategy")
            .asText(participantNode.path("generationStrategy").asText(""))
            .toUpperCase(Locale.ROOT);

    Instant now = clock.now();
    JsonNode ticketData = extractTicketData(eventContext);

    // Resolve participants via ParticipantResolutionEngine
    ParticipantResolverContext resolverContext =
        new ParticipantResolverContext(
            event.getStartedBy(),
            event.getStartedBy(),
            null,
            participantNode.isObject() ? participantNode : JsonNodeFactory.instance.objectNode(),
            now,
            ticketData,
            extractExecutionData(eventContext, execution.getInputJson()),
            null,
            extractTicketSubjects(eventContext));
    ParticipantResolutionEngine.ResolutionOutcome outcome =
        resolutionEngine.resolve(config, resolverContext, resolveWorkflowOwnerId(event));

    if (!outcome.result().isResolved()) {
      if (outcome.onMissingPolicy() == ParticipantResolutionEngine.OnMissingPolicy.CREATE_MANUAL_TASK) {
        createManualTask(event, node, execution, null, outcome.result().reason(), now);
        recordMissingSnapshot(event, execution, null, participantNode, outcome.result(), now);
        return;
      } else {
        recordMissingSnapshot(event, execution, null, participantNode, outcome.result(), now);
        if (outcome.result().reason() != null && outcome.result().reason().contains("Manager not found")) {
          throw new ManagerNotFoundException(outcome.result().reason());
        }
        throw new ParticipantResolutionException(outcome.result().reason());
      }
    }

    List<UUID> resolvedUsers = outcome.result().users();
    String resolverType = outcome.result().resolverType();

    // Check candidate pool (CLAIMABLE_POOL)
    List<UUID> explicitCandidateIds = extractCandidateIds(participantNode, taskNode);
    boolean isClaimablePool =
        strategy.equals("CLAIMABLE_POOL")
            || !explicitCandidateIds.isEmpty()
            || (taskNode.path("candidates").isArray() && !taskNode.path("candidates").isEmpty())
            || (participantNode.path("candidates").isArray()
                && !participantNode.path("candidates").isEmpty()
                && !strategy.equals("TASK_PER_USER"));

    if (isClaimablePool) {
      List<UUID> poolCandidates = !explicitCandidateIds.isEmpty() ? explicitCandidateIds : resolvedUsers;
      TaskExecution task = createClaimableTask(event, node, execution, now);
      if (candidateRepository != null) {
        for (UUID candId : poolCandidates) {
          candidateRepository.save(
              TaskCandidate.create(
                  task.getId(),
                  candId,
                  "CLAIMABLE_POOL",
                  JsonNodeFactory.instance.objectNode(),
                  now));
          recordCandidateSnapshot(event, execution, participantNode, candId, resolverType, now);
        }
        candidateRepository.flush();
      }
      return;
    }

    // Check SEQUENTIAL_TASKS
    if (strategy.equals("SEQUENTIAL_TASKS") && resolvedUsers.size() > 1) {
      UUID firstUserId = resolvedUsers.get(0);
      TaskExecution firstTask =
          createTaskWithAssignee(event, node, execution, null, null, firstUserId, resolverType, now);
      if (candidateRepository != null) {
        for (int i = 1; i < resolvedUsers.size(); i++) {
          ObjectNode seqJson = JsonNodeFactory.instance.objectNode();
          seqJson.put("orderIndex", i);
          seqJson.put("status", "PENDING_TURN");
          candidateRepository.save(
              TaskCandidate.create(
                  firstTask.getId(),
                  resolvedUsers.get(i),
                  "SEQUENTIAL",
                  seqJson,
                  now));
        }
        candidateRepository.flush();
      }
      if (aggregationStateRepository != null) {
        TaskAggregationPolicy policy = parseAggregationPolicy(config, resolvedUsers.size());
        TaskAggregationState state =
            TaskAggregationState.create(execution.getId(), resolvedUsers.size(), policy);
        aggregationStateRepository.saveAndFlush(state);
      }
      return;
    }

    // Check TASK_PER_USER (multiple assignees)
    List<UUID> multiAssignees = extractAssigneeIds(participantNode, taskNode);
    List<UUID> effectiveAssignees = !multiAssignees.isEmpty() ? multiAssignees : resolvedUsers;

    if (effectiveAssignees.size() > 1 || strategy.equals("TASK_PER_USER")) {
      for (UUID assigneeId : effectiveAssignees) {
        createTaskWithAssignee(event, node, execution, null, null, assigneeId, resolverType, now);
      }
      if (aggregationStateRepository != null) {
        TaskAggregationPolicy policy = parseAggregationPolicy(config, effectiveAssignees.size());
        TaskAggregationState state =
            TaskAggregationState.create(execution.getId(), effectiveAssignees.size(), policy);
        aggregationStateRepository.saveAndFlush(state);
      }
      return;
    }

    // DIRECT_SINGLE
    createTaskWithAssignee(
        event,
        node,
        execution,
        null,
        null,
        effectiveAssignees.isEmpty() ? null : effectiveAssignees.get(0),
        resolverType,
        now);
  }

  private void createTask(
      Event event,
      NodeDefinition node,
      NodeExecution execution,
      NodeItemExecution item,
      JsonNode itemData,
      UUID explicitAssigneeId) {
    createTask(event, node, execution, item, itemData, explicitAssigneeId, null);
  }

  private void createTask(
      Event event,
      NodeDefinition node,
      NodeExecution execution,
      NodeItemExecution item,
      JsonNode itemData,
      UUID explicitAssigneeId,
      EventContext eventContext) {
    Instant now = clock.now();
    JsonNode config = node.getConfigJson();
    JsonNode participantNode = config.path("participant");

    UUID resolvedUserId;
    String resolverType;
    if (explicitAssigneeId != null) {
      resolvedUserId = explicitAssigneeId;
      resolverType = "EXPLICIT_USERS";
    } else {
      ParticipantResolverContext resolverContext =
          new ParticipantResolverContext(
              event.getStartedBy(),
              event.getStartedBy(),
              itemData,
              participantNode.isObject() ? participantNode : JsonNodeFactory.instance.objectNode(),
              now,
              extractTicketData(eventContext),
              extractExecutionData(eventContext, execution.getInputJson()),
              null,
              extractTicketSubjects(eventContext));
      ParticipantResolutionEngine.ResolutionOutcome outcome =
          resolutionEngine.resolve(config, resolverContext, resolveWorkflowOwnerId(event));
      if (!outcome.result().isResolved()) {
        if (outcome.onMissingPolicy() == ParticipantResolutionEngine.OnMissingPolicy.CREATE_MANUAL_TASK) {
          createManualTask(event, node, execution, item, outcome.result().reason(), now);
          recordMissingSnapshot(event, execution, item, participantNode, outcome.result(), now);
          return;
        } else {
          recordMissingSnapshot(event, execution, item, participantNode, outcome.result(), now);
          if (outcome.result().reason() != null && outcome.result().reason().contains("Manager not found")) {
            throw new ManagerNotFoundException(outcome.result().reason());
          }
          throw new ParticipantResolutionException(outcome.result().reason());
        }
      }
      resolvedUserId = outcome.result().users().get(0);
      resolverType = outcome.result().resolverType();
    }

    createTaskWithAssignee(event, node, execution, item, itemData, resolvedUserId, resolverType, now);
  }

  private TaskExecution createTaskWithAssignee(
      Event event,
      NodeDefinition node,
      NodeExecution execution,
      NodeItemExecution item,
      JsonNode itemData,
      UUID resolvedUserId,
      String resolverType,
      Instant now) {
    UUID subjectId = itemData != null ? extractOptionalUserId(itemData) : event.getStartedBy();
    String subjectType =
        item == null ? "TICKET_CREATOR" : subjectId == null ? null : "MULTI_INSTANCE_ITEM";

    JsonNode config = node.getConfigJson();
    JsonNode participantNode = config.path("participant");

    ObjectNode snapshotJson = JsonNodeFactory.instance.objectNode();
    snapshotJson.put("resolvedUserId", resolvedUserId != null ? resolvedUserId.toString() : null);
    snapshotJson.put("resolverType", resolverType);

    ParticipantSnapshot snapshot =
        ParticipantSnapshot.create(
            uuidGenerator.generate(),
            event.getId(),
            execution.getId(),
            item == null ? null : item.getId(),
            resolverType != null && !resolverType.isBlank() ? resolverType : "EXPLICIT_USERS",
            "hash-"
                + execution.getId()
                + (item != null ? "-" + item.getId() : "")
                + (resolvedUserId != null ? "-" + resolvedUserId : ""),
            participantNode.isObject() ? participantNode : JsonNodeFactory.instance.objectNode(),
            ParticipantResolutionStatus.RESOLVED,
            subjectType,
            subjectId,
            resolvedUserId,
            "APPROVER",
            snapshotJson,
            now);
    snapshotRepository.save(snapshot);

    String title =
        node.getName() != null && !node.getName().isBlank() ? node.getName() : node.getNodeKey();
    String description = node.getDescription();

    UUID taskId = uuidGenerator.generate();
    Optional<TaskSlaActivationPort.SlaPlan> slaPlan =
        slaActivationService != null ? slaActivationService.plan(node, now) : Optional.empty();
    TaskExecution task =
        TaskExecution.create(
            taskId,
            execution.getId(),
            item == null ? null : item.getId(),
            resolvedUserId,
            title,
            description,
            null,
            execution.getInputJson() != null
                ? execution.getInputJson()
                : JsonNodeFactory.instance.objectNode(),
            50,
            slaPlan.map(TaskSlaActivationPort.SlaPlan::dueAt).orElse(null),
            now);
    taskRepository.saveAndFlush(task);
    if (slaActivationService != null) {
      slaPlan.ifPresent(
          plan -> slaActivationService.record(event.getId(), execution.getId(), taskId, now, plan));
    }
    return task;
  }

  private TaskExecution createManualTask(
      Event event,
      NodeDefinition node,
      NodeExecution execution,
      NodeItemExecution item,
      String reason,
      Instant now) {
    String title =
        node.getName() != null && !node.getName().isBlank() ? node.getName() : node.getNodeKey();
    String description = "[MANUAL RECOVERY] " + (reason != null ? reason : "Participant resolution missing");
    UUID taskId = uuidGenerator.generate();
    Optional<TaskSlaActivationPort.SlaPlan> slaPlan =
        slaActivationService != null ? slaActivationService.plan(node, now) : Optional.empty();
    TaskExecution task =
        TaskExecution.create(
            taskId,
            execution.getId(),
            item == null ? null : item.getId(),
            null,
            title,
            description,
            null,
            execution.getInputJson() != null
                ? execution.getInputJson()
                : JsonNodeFactory.instance.objectNode(),
            50,
            slaPlan.map(TaskSlaActivationPort.SlaPlan::dueAt).orElse(null),
            now);
    taskRepository.saveAndFlush(task);
    if (slaActivationService != null) {
      slaPlan.ifPresent(
          plan -> slaActivationService.record(event.getId(), execution.getId(), taskId, now, plan));
    }
    return task;
  }

  private void recordMissingSnapshot(
      Event event,
      NodeExecution execution,
      NodeItemExecution item,
      JsonNode participantNode,
      ParticipantResolutionResult result,
      Instant now) {
    ObjectNode snapshotJson = JsonNodeFactory.instance.objectNode();
    snapshotJson.put("status", result.status().name());
    snapshotJson.put("reason", result.reason() != null ? result.reason() : "");
    snapshotJson.put("resolverType", result.resolverType() != null ? result.resolverType() : "UNKNOWN");

    ParticipantSnapshot snapshot =
        ParticipantSnapshot.create(
            uuidGenerator.generate(),
            event.getId(),
            execution.getId(),
            item == null ? null : item.getId(),
            result.resolverType() != null && !result.resolverType().isBlank()
                ? result.resolverType()
                : "MANUAL_RECOVERY",
            "hash-" + execution.getId() + "-missing",
            participantNode.isObject() ? participantNode : JsonNodeFactory.instance.objectNode(),
            result.status(),
            "TICKET_CREATOR",
            event.getStartedBy(),
            null,
            "APPROVER",
            snapshotJson,
            now);
    snapshotRepository.save(snapshot);
  }

  private void recordCandidateSnapshot(
      Event event,
      NodeExecution execution,
      JsonNode participantNode,
      UUID candidateId,
      String resolverType,
      Instant now) {
    ObjectNode snapshotJson = JsonNodeFactory.instance.objectNode();
    snapshotJson.put("resolvedUserId", candidateId.toString());
    snapshotJson.put("resolverType", resolverType);
    snapshotJson.put("role", "CANDIDATE");

    ParticipantSnapshot snapshot =
        ParticipantSnapshot.create(
            uuidGenerator.generate(),
            event.getId(),
            execution.getId(),
            null,
            resolverType != null && !resolverType.isBlank() ? resolverType : "CLAIMABLE_POOL",
            "hash-" + execution.getId() + "-" + candidateId,
            participantNode.isObject() ? participantNode : JsonNodeFactory.instance.objectNode(),
            ParticipantResolutionStatus.RESOLVED,
            "TICKET_CREATOR",
            event.getStartedBy(),
            candidateId,
            "CANDIDATE",
            snapshotJson,
            now);
    snapshotRepository.save(snapshot);
  }

  private TaskExecution createClaimableTask(
      Event event, NodeDefinition node, NodeExecution execution, Instant now) {
    String title =
        node.getName() != null && !node.getName().isBlank() ? node.getName() : node.getNodeKey();
    String description = node.getDescription();
    UUID taskId = uuidGenerator.generate();
    Optional<TaskSlaActivationPort.SlaPlan> slaPlan =
        slaActivationService != null ? slaActivationService.plan(node, now) : Optional.empty();
    TaskExecution task =
        TaskExecution.create(
            taskId,
            execution.getId(),
            null,
            null,
            title,
            description,
            null,
            execution.getInputJson() != null
                ? execution.getInputJson()
                : JsonNodeFactory.instance.objectNode(),
            50,
            slaPlan.map(TaskSlaActivationPort.SlaPlan::dueAt).orElse(null),
            now);
    taskRepository.saveAndFlush(task);
    if (slaActivationService != null) {
      slaPlan.ifPresent(
          plan -> slaActivationService.record(event.getId(), execution.getId(), taskId, now, plan));
    }
    return task;
  }

  private List<UUID> extractAssigneeIds(JsonNode participantNode, JsonNode taskNode) {
    List<UUID> result = new ArrayList<>();
    JsonNode array = null;
    if (participantNode.path("users").isArray() && !participantNode.path("users").isEmpty()) {
      array = participantNode.path("users");
    } else if (participantNode.path("userIds").isArray()
        && !participantNode.path("userIds").isEmpty()) {
      array = participantNode.path("userIds");
    } else if (participantNode.path("assignees").isArray()
        && !participantNode.path("assignees").isEmpty()) {
      array = participantNode.path("assignees");
    } else if (taskNode.path("assignees").isArray() && !taskNode.path("assignees").isEmpty()) {
      array = taskNode.path("assignees");
    } else if (taskNode.path("users").isArray() && !taskNode.path("users").isEmpty()) {
      array = taskNode.path("users");
    }
    if (array != null) {
      for (JsonNode elem : array) {
        try {
          result.add(UUID.fromString(elem.asText()));
        } catch (IllegalArgumentException ignored) {
        }
      }
    }
    return result;
  }

  private List<UUID> extractCandidateIds(JsonNode participantNode, JsonNode taskNode) {
    List<UUID> result = new ArrayList<>();
    JsonNode array = null;
    if (participantNode.path("candidates").isArray()
        && !participantNode.path("candidates").isEmpty()) {
      array = participantNode.path("candidates");
    } else if (taskNode.path("candidates").isArray() && !taskNode.path("candidates").isEmpty()) {
      array = taskNode.path("candidates");
    }
    if (array != null) {
      for (JsonNode elem : array) {
        try {
          result.add(UUID.fromString(elem.asText()));
        } catch (IllegalArgumentException ignored) {
        }
      }
    }
    return result;
  }

  private TaskAggregationPolicy parseAggregationPolicy(JsonNode config, int totalTasks) {
    JsonNode taskNode = config.path("task");
    JsonNode aggNode =
        config.hasNonNull("aggregation")
            ? config.path("aggregation")
            : (taskNode.hasNonNull("decisionAggregationPolicy")
                    || taskNode.hasNonNull("completionPolicy")
                ? taskNode
                : config);

    DecisionAggregationPolicy decisionPolicy = null;
    CompletionPolicy completionPolicy = null;

    String decStr =
        aggNode.hasNonNull("decisionAggregationPolicy")
            ? aggNode.path("decisionAggregationPolicy").asText()
            : aggNode.path("decisionPolicy").asText(null);

    if (decStr != null && !decStr.isBlank()) {
      decisionPolicy = parseDecisionPolicy(decStr);
    }

    String compStr =
        aggNode.hasNonNull("completionPolicy") ? aggNode.path("completionPolicy").asText() : null;

    if (compStr != null && !compStr.isBlank() && decisionPolicy == null) {
      try {
        completionPolicy = CompletionPolicy.valueOf(compStr.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
      }
    }

    if (decisionPolicy == null && completionPolicy == null) {
      decisionPolicy = DecisionAggregationPolicy.ALL_APPROVE;
    }

    boolean requiresThreshold =
        decisionPolicy == DecisionAggregationPolicy.N_OF_M_APPROVE
            || decisionPolicy == DecisionAggregationPolicy.PERCENTAGE_APPROVE
            || completionPolicy == CompletionPolicy.N_OF_M
            || completionPolicy == CompletionPolicy.PERCENTAGE;

    Integer threshold = null;
    if (aggNode.hasNonNull("threshold")) {
      threshold = aggNode.path("threshold").asInt();
    }
    if (!requiresThreshold) {
      threshold = null;
    } else if (threshold == null || threshold <= 0) {
      if (decisionPolicy == DecisionAggregationPolicy.PERCENTAGE_APPROVE
          || completionPolicy == CompletionPolicy.PERCENTAGE) {
        threshold = 50;
      } else {
        threshold = Math.min(1, totalTasks);
      }
    }

    String rejStr = aggNode.path("rejectBehavior").asText("FAIL_FAST");
    RejectBehavior rejectBehavior = RejectBehavior.FAIL_FAST;
    try {
      rejectBehavior = RejectBehavior.valueOf(rejStr.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
    }

    String remStr = aggNode.path("remainingTaskBehavior").asText("CANCEL_REMAINING");
    RemainingTaskBehavior remainingTaskBehavior = RemainingTaskBehavior.CANCEL_REMAINING;
    try {
      remainingTaskBehavior = RemainingTaskBehavior.valueOf(remStr.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
    }

    return new TaskAggregationPolicy(
        decisionPolicy, completionPolicy, threshold, rejectBehavior, remainingTaskBehavior);
  }

  private DecisionAggregationPolicy parseDecisionPolicy(String value) {
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "ALL", "ALL_APPROVE" -> DecisionAggregationPolicy.ALL_APPROVE;
      case "ANY", "ANY_APPROVE", "FIRST" -> DecisionAggregationPolicy.ANY_APPROVE;
      case "MAJORITY", "MAJORITY_APPROVE" -> DecisionAggregationPolicy.MAJORITY_APPROVE;
      case "N_OF_M", "N_OF_M_APPROVE" -> DecisionAggregationPolicy.N_OF_M_APPROVE;
      case "PERCENTAGE", "PERCENTAGE_APPROVE" -> DecisionAggregationPolicy.PERCENTAGE_APPROVE;
      default -> {
        try {
          yield DecisionAggregationPolicy.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
          yield DecisionAggregationPolicy.ALL_APPROVE;
        }
      }
    };
  }

  private UUID extractOptionalUserId(JsonNode itemData) {
    if (itemData == null || itemData.isNull()) {
      return null;
    }
    String raw = itemData.isTextual() ? itemData.asText() : itemData.path("id").asText(null);
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ignored) {
      // A generic multi-instance item is not necessarily a USER business subject.
      return null;
    }
  }

  private JsonNode extractTicketData(EventContext eventContext) {
    if (eventContext == null) {
      return null;
    }
    com.fasterxml.jackson.databind.node.ObjectNode val = eventContext.value();
    if (val == null) {
      return null;
    }
    if (val.has("ticket") && val.path("ticket").has("data")) {
      JsonNode ticketData = val.path("ticket").path("data");
      if (ticketData.isObject()) {
        com.fasterxml.jackson.databind.node.ObjectNode merged = ticketData.deepCopy();
        val.fieldNames().forEachRemaining(field -> {
          if (!"ticket".equals(field) && !merged.has(field)) {
            merged.set(field, val.get(field));
          }
        });
        return merged;
      }
      return ticketData;
    }
    return val;
  }

  private JsonNode extractTicketSubjects(EventContext eventContext) {
    if (eventContext == null) return JsonNodeFactory.instance.arrayNode();
    ObjectNode value = eventContext.value();
    if (value == null) return JsonNodeFactory.instance.arrayNode();
    JsonNode subjects = value.path("ticket").path("subjects");
    return subjects.isArray() ? subjects : JsonNodeFactory.instance.arrayNode();
  }

  private JsonNode extractExecutionData(EventContext eventContext, JsonNode fallback) {
    if (eventContext != null) {
      ObjectNode value = eventContext.value();
      if (value != null) {
        JsonNode nodeData = value.path("nodes");
        if (nodeData.isObject()) return nodeData;
      }
    }
    return fallback != null ? fallback : JsonNodeFactory.instance.objectNode();
  }

  private UUID resolveWorkflowOwnerId(Event event) {
    if (workflowVersions == null || workflowDefinitions == null) return null;
    return workflowVersions
        .findById(event.getWorkflowVersionId())
        .flatMap(version -> workflowDefinitions.findById(version.getDefinitionId()))
        .map(definition -> definition.getOwnerId())
        .orElse(null);
  }
}
