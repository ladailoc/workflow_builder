package com.fpt.workflow.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.nodetype.NodeCapability;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import com.fpt.workflow.resolver.participant.ParticipantResolutionEngine;
import com.fpt.workflow.resolver.participant.ParticipantResolutionException;
import com.fpt.workflow.resolver.participant.ParticipantResolverRegistry;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.multiinstance.domain.NodeItemExecution;
import com.fpt.workflow.runtime.multiinstance.repository.NodeItemExecutionRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.aggregation.TaskAggregationStateRepository;
import com.fpt.workflow.task.domain.TaskCandidate;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskCandidateRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HumanTaskParticipantActivationHookTest {

  private TaskExecutionRepository taskRepository;
  private ParticipantSnapshotRepository snapshotRepository;
  private NodeTypeRegistry registry;
  private OrganizationHierarchyService hierarchyService;
  private UuidGenerator uuidGenerator;
  private PlatformClock clock;
  private ObjectMapper objectMapper;
  private TaskSlaActivationPort slaActivationService;
  private NodeItemExecutionRepository itemRepository;
  private ParticipantResolverRegistry participantRegistry;
  private TaskAggregationStateRepository aggregationStateRepository;
  private TaskCandidateRepository candidateRepository;
  private ParticipantResolutionEngine resolutionEngine;

  private HumanTaskParticipantActivationHook hook;

  private final Instant now = Instant.parse("2026-09-11T00:00:00Z");

  @BeforeEach
  void setUp() {
    taskRepository = mock(TaskExecutionRepository.class);
    snapshotRepository = mock(ParticipantSnapshotRepository.class);
    registry = mock(NodeTypeRegistry.class);
    hierarchyService = mock(OrganizationHierarchyService.class);
    uuidGenerator = UUID::randomUUID;
    clock = () -> now;
    objectMapper = new ObjectMapper();
    slaActivationService = mock(TaskSlaActivationPort.class);
    itemRepository = mock(NodeItemExecutionRepository.class);
    participantRegistry = mock(ParticipantResolverRegistry.class);
    aggregationStateRepository = mock(TaskAggregationStateRepository.class);
    candidateRepository = mock(TaskCandidateRepository.class);
    resolutionEngine = mock(ParticipantResolutionEngine.class);

    NodeTypeManifest manifest = mock(NodeTypeManifest.class);
    when(manifest.supportedCapabilities()).thenReturn(Set.of(NodeCapability.HUMAN_TASK));
    when(registry.require(NodeType.APPROVAL)).thenReturn(manifest);

    hook =
        new HumanTaskParticipantActivationHook(
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
            resolutionEngine);
  }

  private Event createEvent(UUID startedBy) {
    Event event = mock(Event.class);
    when(event.getId()).thenReturn(UUID.randomUUID());
    when(event.getStartedBy()).thenReturn(startedBy);
    return event;
  }

  private NodeExecution createExecution(UUID eventId, String nodeKey) {
    NodeExecution exec =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            UUID.randomUUID(),
            nodeKey,
            UUID.randomUUID(),
            0,
            "root",
            null,
            null,
            null,
            JsonNodeFactory.instance.objectNode(),
            UUID.randomUUID(),
            now);
    exec.markReady();
    exec.start(now);
    return exec;
  }

  private NodeDefinition createNode(String nodeKey, ObjectNode config) {
    return NodeDefinition.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        nodeKey,
        "APPROVAL",
        "Approval Node",
        "description",
        1,
        config,
        null,
        null,
        JsonNodeFactory.instance.objectNode());
  }

  @Test
  void testDirectSingle_AssignsSingleTaskToResolvedUser() {
    UUID creatorId = UUID.randomUUID();
    UUID assigneeId = UUID.randomUUID();
    Event event = createEvent(creatorId);
    NodeExecution execution = createExecution(event.getId(), "approve-step");

    ObjectNode nodeConfig = JsonNodeFactory.instance.objectNode();
    ObjectNode taskConfig = nodeConfig.putObject("task");
    taskConfig.put("generationStrategy", "DIRECT_SINGLE");
    ObjectNode participantConfig = nodeConfig.putObject("participant");
    participantConfig.put("type", "FIXED_USER");

    NodeDefinition node = createNode("approve-step", nodeConfig);

    when(resolutionEngine.resolve(any(), any(), any()))
        .thenReturn(new ParticipantResolutionEngine.ResolutionOutcome(
            ParticipantResolutionResult.resolved(List.of(assigneeId), "FIXED_USER"),
            ParticipantResolutionEngine.OnMissingPolicy.FAIL_NODE,
            "PRIMARY",
            List.of()));

    EventContext eventContext = mock(EventContext.class);

    hook.onActivation(event, node, execution, eventContext);

    ArgumentCaptor<TaskExecution> taskCaptor = ArgumentCaptor.forClass(TaskExecution.class);
    verify(taskRepository).saveAndFlush(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getAssigneeId()).isEqualTo(assigneeId);
  }

  @Test
  void explicitWorkflowOwnerFallbackReceivesTheBoundDefinitionOwner() {
    UUID creatorId = UUID.randomUUID();
    UUID workflowVersionId = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    Event event = createEvent(creatorId);
    when(event.getWorkflowVersionId()).thenReturn(workflowVersionId);
    NodeExecution execution = createExecution(event.getId(), "owner-fallback-step");
    ObjectNode nodeConfig = JsonNodeFactory.instance.objectNode();
    nodeConfig.putObject("participant").put("type", "FIXED_USER");
    NodeDefinition node = createNode("owner-fallback-step", nodeConfig);
    WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    WorkflowVersion version = mock(WorkflowVersion.class);
    WorkflowDefinition definition = mock(WorkflowDefinition.class);
    when(version.getDefinitionId()).thenReturn(definitionId);
    when(definition.getOwnerId()).thenReturn(ownerId);
    when(versions.findById(workflowVersionId)).thenReturn(java.util.Optional.of(version));
    when(definitions.findById(definitionId)).thenReturn(java.util.Optional.of(definition));
    when(resolutionEngine.resolve(any(), any(), org.mockito.ArgumentMatchers.eq(ownerId)))
        .thenReturn(
            new ParticipantResolutionEngine.ResolutionOutcome(
                ParticipantResolutionResult.resolved(ownerId, "WORKFLOW_OWNER"),
                ParticipantResolutionEngine.OnMissingPolicy.FAIL_NODE,
                "FALLBACK_1",
                List.of()));
    HumanTaskParticipantActivationHook productionHook =
        new HumanTaskParticipantActivationHook(
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
            versions,
            definitions);

    productionHook.onActivation(event, node, execution, mock(EventContext.class));

    verify(resolutionEngine).resolve(any(), any(), org.mockito.ArgumentMatchers.eq(ownerId));
  }

  @Test
  void testClaimablePool_CreatesUnassignedTaskAndCandidateRecords() {
    UUID creatorId = UUID.randomUUID();
    UUID user1 = UUID.randomUUID();
    UUID user2 = UUID.randomUUID();
    Event event = createEvent(creatorId);
    NodeExecution execution = createExecution(event.getId(), "pool-step");

    ObjectNode nodeConfig = JsonNodeFactory.instance.objectNode();
    ObjectNode taskConfig = nodeConfig.putObject("task");
    taskConfig.put("generationStrategy", "CLAIMABLE_POOL");
    ObjectNode participantConfig = nodeConfig.putObject("participant");
    participantConfig.put("type", "ROLE_MEMBERS");

    NodeDefinition node = createNode("pool-step", nodeConfig);

    when(resolutionEngine.resolve(any(), any(), any()))
        .thenReturn(new ParticipantResolutionEngine.ResolutionOutcome(
            ParticipantResolutionResult.resolved(List.of(user1, user2), "ROLE_MEMBERS"),
            ParticipantResolutionEngine.OnMissingPolicy.FAIL_NODE,
            "PRIMARY",
            List.of()));

    EventContext eventContext = mock(EventContext.class);

    hook.onActivation(event, node, execution, eventContext);

    // Verify task is unassigned
    ArgumentCaptor<TaskExecution> taskCaptor = ArgumentCaptor.forClass(TaskExecution.class);
    verify(taskRepository).saveAndFlush(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getAssigneeId()).isNull();

    // Verify candidates created
    ArgumentCaptor<TaskCandidate> candidateCaptor = ArgumentCaptor.forClass(TaskCandidate.class);
    verify(candidateRepository, times(2)).save(candidateCaptor.capture());
    List<UUID> candidateUserIds = candidateCaptor.getAllValues().stream().map(TaskCandidate::getUserId).toList();
    assertThat(candidateUserIds).containsExactlyInAnyOrder(user1, user2);
    assertThat(candidateCaptor.getAllValues()).allMatch(c -> c.getSourceType().equals("CLAIMABLE_POOL"));
  }

  @Test
  void testTaskPerUser_CreatesMultipleParallelTasks() {
    UUID creatorId = UUID.randomUUID();
    UUID user1 = UUID.randomUUID();
    UUID user2 = UUID.randomUUID();
    Event event = createEvent(creatorId);
    NodeExecution execution = createExecution(event.getId(), "multi-task-step");

    ObjectNode nodeConfig = JsonNodeFactory.instance.objectNode();
    ObjectNode taskConfig = nodeConfig.putObject("task");
    taskConfig.put("generationStrategy", "TASK_PER_USER");

    NodeDefinition node = createNode("multi-task-step", nodeConfig);

    when(resolutionEngine.resolve(any(), any(), any()))
        .thenReturn(new ParticipantResolutionEngine.ResolutionOutcome(
            ParticipantResolutionResult.resolved(List.of(user1, user2), "GROUP_MEMBERS"),
            ParticipantResolutionEngine.OnMissingPolicy.FAIL_NODE,
            "PRIMARY",
            List.of()));

    EventContext eventContext = mock(EventContext.class);

    hook.onActivation(event, node, execution, eventContext);

    ArgumentCaptor<TaskExecution> taskCaptor = ArgumentCaptor.forClass(TaskExecution.class);
    verify(taskRepository, times(2)).saveAndFlush(taskCaptor.capture());
    List<UUID> assignees = taskCaptor.getAllValues().stream().map(TaskExecution::getAssigneeId).toList();
    assertThat(assignees).containsExactlyInAnyOrder(user1, user2);
  }

  @Test
  void testSequentialTasks_CreatesFirstTaskAndPendingTurnCandidates() {
    UUID creatorId = UUID.randomUUID();
    UUID user1 = UUID.randomUUID();
    UUID user2 = UUID.randomUUID();
    Event event = createEvent(creatorId);
    NodeExecution execution = createExecution(event.getId(), "seq-step");

    ObjectNode nodeConfig = JsonNodeFactory.instance.objectNode();
    ObjectNode taskConfig = nodeConfig.putObject("task");
    taskConfig.put("generationStrategy", "SEQUENTIAL_TASKS");

    NodeDefinition node = createNode("seq-step", nodeConfig);

    when(resolutionEngine.resolve(any(), any(), any()))
        .thenReturn(new ParticipantResolutionEngine.ResolutionOutcome(
            ParticipantResolutionResult.resolved(List.of(user1, user2), "FIXED_USER"),
            ParticipantResolutionEngine.OnMissingPolicy.FAIL_NODE,
            "PRIMARY",
            List.of()));

    EventContext eventContext = mock(EventContext.class);

    hook.onActivation(event, node, execution, eventContext);

    // First task should be assigned to user1
    ArgumentCaptor<TaskExecution> taskCaptor = ArgumentCaptor.forClass(TaskExecution.class);
    verify(taskRepository).saveAndFlush(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getAssigneeId()).isEqualTo(user1);

    // Remaining user2 should be saved as candidate with pending sequence
    ArgumentCaptor<TaskCandidate> candidateCaptor = ArgumentCaptor.forClass(TaskCandidate.class);
    verify(candidateRepository).save(candidateCaptor.capture());
    assertThat(candidateCaptor.getValue().getUserId()).isEqualTo(user2);
    assertThat(candidateCaptor.getValue().getSourceSnapshotJson().path("status").asText()).isEqualTo("PENDING_TURN");
  }

  @Test
  void testOnMissingPolicy_FailNode_ThrowsException() {
    UUID creatorId = UUID.randomUUID();
    Event event = createEvent(creatorId);
    NodeExecution execution = createExecution(event.getId(), "fail-step");

    ObjectNode nodeConfig = JsonNodeFactory.instance.objectNode();
    ObjectNode participantConfig = nodeConfig.putObject("participant");
    participantConfig.put("onMissingPolicy", "FAIL_NODE");

    NodeDefinition node = createNode("fail-step", nodeConfig);

    when(resolutionEngine.resolve(any(), any(), any()))
        .thenReturn(new ParticipantResolutionEngine.ResolutionOutcome(
            ParticipantResolutionResult.vacant("No manager found in hierarchy", "MANAGER_OF"),
            ParticipantResolutionEngine.OnMissingPolicy.FAIL_NODE,
            "PRIMARY",
            List.of()));

    EventContext eventContext = mock(EventContext.class);

    assertThatThrownBy(() -> hook.onActivation(event, node, execution, eventContext))
        .isInstanceOf(ParticipantResolutionException.class)
        .hasMessageContaining("No manager found in hierarchy");
  }

  @Test
  void testOnMissingPolicy_CreateManualTask_CreatesManualUnassignedTask() {
    UUID creatorId = UUID.randomUUID();
    Event event = createEvent(creatorId);
    NodeExecution execution = createExecution(event.getId(), "manual-step");

    ObjectNode nodeConfig = JsonNodeFactory.instance.objectNode();
    ObjectNode participantConfig = nodeConfig.putObject("participant");
    participantConfig.put("onMissingPolicy", "CREATE_MANUAL_TASK");

    NodeDefinition node = createNode("manual-step", nodeConfig);

    when(resolutionEngine.resolve(any(), any(), any()))
        .thenReturn(new ParticipantResolutionEngine.ResolutionOutcome(
            ParticipantResolutionResult.vacant("Position vacant", "MANAGER_OF"),
            ParticipantResolutionEngine.OnMissingPolicy.CREATE_MANUAL_TASK,
            "EXHAUSTED",
            List.of()));

    EventContext eventContext = mock(EventContext.class);

    hook.onActivation(event, node, execution, eventContext);

    ArgumentCaptor<TaskExecution> taskCaptor = ArgumentCaptor.forClass(TaskExecution.class);
    verify(taskRepository).saveAndFlush(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getAssigneeId()).isNull();
    assertThat(taskCaptor.getValue().getDescriptionSnapshot()).contains("[MANUAL RECOVERY]");
  }

  @Test
  void testMultiInstance_BindsItemVariableAndItemContext_ForTaskCreation() {
    UUID creatorId = UUID.randomUUID();
    Event event = createEvent(creatorId);
    NodeExecution execution = createExecution(event.getId(), "mi-task-step");

    UUID user1 = UUID.randomUUID();
    UUID user2 = UUID.randomUUID();

    ObjectNode itemData1 = objectMapper.createObjectNode().put("id", user1.toString()).put("name", "Alice");
    ObjectNode itemData2 = objectMapper.createObjectNode().put("id", user2.toString()).put("name", "Bob");

    NodeItemExecution item1 =
        NodeItemExecution.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            event.getId(),
            execution.getId(),
            0,
            "item-0-" + user1,
            itemData1,
            now);
    NodeItemExecution item2 =
        NodeItemExecution.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            event.getId(),
            execution.getId(),
            1,
            "item-1-" + user2,
            itemData2,
            now);

    when(itemRepository.findAllByParentNodeExecutionIdOrderByItemIndexAsc(execution.getId()))
        .thenReturn(List.of(item1, item2));

    ObjectNode nodeConfig = JsonNodeFactory.instance.objectNode();
    ObjectNode miConfig = nodeConfig.putObject("multiInstance");
    miConfig.put("collection", "${ticket.data.employees}");
    miConfig.put("itemVariable", "employee");

    ObjectNode participantConfig = nodeConfig.putObject("participant");
    participantConfig.put("type", "EXPRESSION");
    participantConfig.put("expression", "${employee.id}");

    NodeDefinition node = createNode("mi-task-step", nodeConfig);

    when(resolutionEngine.resolve(any(), any(), any()))
        .thenAnswer(inv -> {
          com.fpt.workflow.resolver.participant.ParticipantResolverContext prc = inv.getArgument(1);
          if (prc.item() != null && prc.item().hasNonNull("id")) {
            UUID uId = UUID.fromString(prc.item().get("id").asText());
            return new ParticipantResolutionEngine.ResolutionOutcome(
                ParticipantResolutionResult.resolved(List.of(uId), "EXPRESSION"),
                ParticipantResolutionEngine.OnMissingPolicy.FAIL_NODE,
                "PRIMARY",
                List.of());
          }
          return new ParticipantResolutionEngine.ResolutionOutcome(
              ParticipantResolutionResult.resolved(List.of(creatorId), "EXPRESSION"),
              ParticipantResolutionEngine.OnMissingPolicy.FAIL_NODE,
              "PRIMARY",
              List.of());
        });

    EventContext eventContext = mock(EventContext.class);
    when(eventContext.withItemVariable(any(), any())).thenReturn(eventContext);
    when(eventContext.value()).thenReturn(objectMapper.createObjectNode());

    hook.onActivation(event, node, execution, eventContext);

    // Verify enriched EventContext called with configured itemVariable "employee"
    verify(eventContext).withItemVariable("employee", itemData1);
    verify(eventContext).withItemVariable("employee", itemData2);

    // Verify 2 tasks created with user1 and user2
    ArgumentCaptor<TaskExecution> taskCaptor = ArgumentCaptor.forClass(TaskExecution.class);
    verify(taskRepository, times(2)).saveAndFlush(taskCaptor.capture());
    List<TaskExecution> createdTasks = taskCaptor.getAllValues();
    assertThat(createdTasks).hasSize(2);
    assertThat(createdTasks.get(0).getAssigneeId()).isEqualTo(user1);
    assertThat(createdTasks.get(1).getAssigneeId()).isEqualTo(user2);
  }
}
