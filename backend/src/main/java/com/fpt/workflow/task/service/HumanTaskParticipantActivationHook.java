package com.fpt.workflow.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.nodetype.NodeCapability;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.participant.CreatorParticipantResolver;
import com.fpt.workflow.resolver.participant.FixedUserParticipantResolver;
import com.fpt.workflow.resolver.participant.ItemManagerParticipantResolver;
import com.fpt.workflow.resolver.participant.ItemUserParticipantResolver;
import com.fpt.workflow.resolver.participant.ManagerOfParticipantResolver;
import com.fpt.workflow.resolver.participant.ParticipantResolverContext;
import com.fpt.workflow.resolver.participant.ParticipantResolverRegistry;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.activation.ParticipantActivationHook;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.multiinstance.domain.NodeItemExecution;
import com.fpt.workflow.runtime.multiinstance.repository.NodeItemExecutionRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.List;
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
      ParticipantResolverRegistry participantRegistry) {
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
        defaultRegistry(hierarchyService));
  }

  private static ParticipantResolverRegistry defaultRegistry(
      OrganizationHierarchyService hierarchyService) {
    return new ParticipantResolverRegistry(
        List.of(
            new FixedUserParticipantResolver(),
            new CreatorParticipantResolver(),
            new ManagerOfParticipantResolver(hierarchyService),
            new ItemUserParticipantResolver(),
            new ItemManagerParticipantResolver(hierarchyService)));
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
      for (NodeItemExecution item :
          itemRepository.findAllByParentNodeExecutionIdOrderByItemIndexAsc(execution.getId())) {
        createTask(event, node, execution, item, item.getItemDataJson());
      }
      return;
    }
    createTask(event, node, execution, null, null);
  }

  private void createTask(
      Event event,
      NodeDefinition node,
      NodeExecution execution,
      NodeItemExecution item,
      JsonNode itemData) {
    Instant now = clock.now();
    JsonNode config = node.getConfigJson();
    JsonNode participantNode = config.path("participant");

    ObjectNode effectiveConfig =
        participantNode.isObject()
            ? participantNode.deepCopy()
            : JsonNodeFactory.instance.objectNode();
    if (!effectiveConfig.hasNonNull("type")) {
      effectiveConfig.put("type", "MANAGER_OF");
    }
    String resolverType = effectiveConfig.path("type").asText();

    ParticipantResolverContext resolverContext =
        new ParticipantResolverContext(
            event.getStartedBy(), event.getStartedBy(), itemData, effectiveConfig, now);
    UUID resolvedUserId = participantRegistry.resolve(resolverType, resolverContext);
    UUID subjectId = itemData != null ? extractOptionalUserId(itemData) : event.getStartedBy();
    String subjectType =
        item == null ? "TICKET_CREATOR" : subjectId == null ? null : "MULTI_INSTANCE_ITEM";

    ObjectNode snapshotJson = JsonNodeFactory.instance.objectNode();
    snapshotJson.put("resolvedUserId", resolvedUserId.toString());
    snapshotJson.put("resolverType", resolverType);

    ParticipantSnapshot snapshot =
        ParticipantSnapshot.create(
            uuidGenerator.generate(),
            event.getId(),
            execution.getId(),
            item == null ? null : item.getId(),
            resolverType,
            "hash-" + execution.getId(),
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
    java.util.Optional<TaskSlaActivationPort.SlaPlan> slaPlan =
        slaActivationService.plan(node, now);
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
    slaPlan.ifPresent(
        plan -> slaActivationService.record(event.getId(), execution.getId(), taskId, now, plan));
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
}
