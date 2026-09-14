package com.fpt.workflow.task.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.lifecycle.EventLifecycleService;
import com.fpt.workflow.runtime.multiinstance.repository.NodeItemExecutionRepository;
import com.fpt.workflow.runtime.multiinstance.service.MultiInstanceService;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskDecision;
import com.fpt.workflow.task.domain.TaskCandidate;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskCandidateRepository;
import com.fpt.workflow.task.repository.TaskDecisionRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.task.service.TaskCommandService;
import com.fpt.workflow.task.service.TaskSlaActivationPort;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TaskAggregationDecisionTest {

  private TaskExecutionRepository taskRepository;
  private TaskDecisionRepository decisionRepository;
  private NodeExecutionRepository executionRepository;
  private RoutingService routingService;
  private EventLifecycleService eventLifecycleService;
  private AuditEventRepository auditRepository;
  private ActorContextProvider actorProvider;
  private UuidGenerator uuidGenerator;
  private PlatformClock clock;
  private MultiInstanceService multiInstanceService;
  private NodeItemExecutionRepository itemExecutionRepository;
  private TaskSlaActivationPort slaActivationService;
  private TaskCandidateRepository candidateRepository;

  private TaskAggregationStateRepository stateRepository;
  private TaskAggregationVoteRepository voteRepository;
  private TaskAggregationService aggregationService;
  private TaskCommandService commandService;

  private final Instant now = Instant.parse("2026-03-30T10:00:00Z");
  private final UUID eventId = UUID.randomUUID();
  private final UUID nodeExecutionId = UUID.randomUUID();
  private final UUID approver1 = UUID.randomUUID();
  private final UUID approver2 = UUID.randomUUID();
  private final UUID approver3 = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    taskRepository = mock(TaskExecutionRepository.class);
    decisionRepository = mock(TaskDecisionRepository.class);
    executionRepository = mock(NodeExecutionRepository.class);
    routingService = mock(RoutingService.class);
    eventLifecycleService = mock(EventLifecycleService.class);
    auditRepository = mock(AuditEventRepository.class);
    actorProvider = mock(ActorContextProvider.class);
    uuidGenerator = UUID::randomUUID;
    clock = () -> now;
    multiInstanceService = mock(MultiInstanceService.class);
    itemExecutionRepository = mock(NodeItemExecutionRepository.class);
    slaActivationService = mock(TaskSlaActivationPort.class);
    candidateRepository = mock(TaskCandidateRepository.class);

    stateRepository = mock(TaskAggregationStateRepository.class);
    voteRepository = mock(TaskAggregationVoteRepository.class);
    aggregationService = new TaskAggregationService(stateRepository, voteRepository, clock);

    commandService =
        new TaskCommandService(
            taskRepository,
            decisionRepository,
            executionRepository,
            routingService,
            eventLifecycleService,
            auditRepository,
            actorProvider,
            uuidGenerator,
            clock,
            multiInstanceService,
            itemExecutionRepository,
            slaActivationService,
            aggregationService,
            candidateRepository);
  }

  @Test
  @DisplayName("Multi-approver ALL_APPROVE: node does not complete until final approval")
  void allApprovePolicyWaitsForAllApprovers() {
    UUID task1Id = UUID.randomUUID();
    UUID task2Id = UUID.randomUUID();

    TaskExecution task1 = createTask(task1Id, nodeExecutionId, approver1);
    TaskExecution task2 = createTask(task2Id, nodeExecutionId, approver2);

    NodeExecution nodeExec = createNodeExecution(nodeExecutionId, eventId);

    TaskAggregationState aggState =
        TaskAggregationState.create(
            nodeExecutionId,
            2,
            new TaskAggregationPolicy(
                DecisionAggregationPolicy.ALL_APPROVE,
                null,
                null,
                RejectBehavior.WAIT_ALL,
                RemainingTaskBehavior.CANCEL_REMAINING));

    when(taskRepository.findById(task1Id)).thenReturn(Optional.of(task1));
    when(taskRepository.findById(task2Id)).thenReturn(Optional.of(task2));
    when(executionRepository.findById(nodeExecutionId)).thenReturn(Optional.of(nodeExec));
    when(executionRepository.findByIdForUpdate(nodeExecutionId)).thenReturn(Optional.of(nodeExec));
    when(stateRepository.existsById(nodeExecutionId)).thenReturn(true);
    when(stateRepository.findById(nodeExecutionId)).thenReturn(Optional.of(aggState));
    when(stateRepository.findByIdForUpdate(nodeExecutionId)).thenReturn(Optional.of(aggState));
    when(voteRepository.findById(any())).thenReturn(Optional.empty());

    RoutingResult mockRouting = mock(RoutingResult.class);
    when(routingService.route(any(), any(), any())).thenReturn(mockRouting);

    // 1. Approver 1 approves
    when(actorProvider.requireActor())
        .thenReturn(new ActorContext(approver1, "user1", Set.of(), Set.of()));

    TaskCommandService.TaskDecisionResult result1 =
        commandService.decideTask(
            task1Id,
            BusinessOutcome.APPROVED,
            JsonNodeFactory.instance.objectNode(),
            "Looks good",
            new CorrelationId(UUID.randomUUID()),
            new CommandId(UUID.randomUUID()));

    assertThat(result1.task().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(result1.task().getOutcome()).isEqualTo("APPROVED");
    assertThat(result1.routingResult()).isNull(); // Node is not yet complete!
    verify(executionRepository, never()).saveAndFlush(nodeExec);
    verify(routingService, never()).route(any(), any(), any());

    // 2. Approver 2 approves
    when(actorProvider.requireActor())
        .thenReturn(new ActorContext(approver2, "user2", Set.of(), Set.of()));

    TaskCommandService.TaskDecisionResult result2 =
        commandService.decideTask(
            task2Id,
            BusinessOutcome.APPROVED,
            JsonNodeFactory.instance.objectNode(),
            "Also approved",
            new CorrelationId(UUID.randomUUID()),
            new CommandId(UUID.randomUUID()));

    assertThat(result2.task().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(result2.routingResult()).isNotNull(); // Now complete and routed!
    assertThat(nodeExec.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(nodeExec.getOutcomePort()).isEqualTo("APPROVED");
    verify(executionRepository).saveAndFlush(nodeExec);
    verify(routingService).route(eq(nodeExecutionId), any(), any());
  }

  @Test
  @DisplayName("Multi-approver FAIL_FAST: single rejection cancels remaining sibling tasks")
  void failFastRejectionCancelsRemainingSiblingTasks() {
    UUID task1Id = UUID.randomUUID();
    UUID task2Id = UUID.randomUUID();
    UUID task3Id = UUID.randomUUID();

    TaskExecution task1 = createTask(task1Id, nodeExecutionId, approver1);
    TaskExecution task2 = createTask(task2Id, nodeExecutionId, approver2);
    TaskExecution task3 = createTask(task3Id, nodeExecutionId, approver3);

    NodeExecution nodeExec = createNodeExecution(nodeExecutionId, eventId);

    TaskAggregationState aggState =
        TaskAggregationState.create(
            nodeExecutionId,
            3,
            new TaskAggregationPolicy(
                DecisionAggregationPolicy.ALL_APPROVE,
                null,
                null,
                RejectBehavior.FAIL_FAST,
                RemainingTaskBehavior.CANCEL_REMAINING));

    when(taskRepository.findById(task1Id)).thenReturn(Optional.of(task1));
    when(taskRepository.findAllByNodeExecutionIdOrderByCreatedAtAsc(nodeExecutionId))
        .thenReturn(List.of(task1, task2, task3));
    when(executionRepository.findByIdForUpdate(nodeExecutionId)).thenReturn(Optional.of(nodeExec));
    when(stateRepository.existsById(nodeExecutionId)).thenReturn(true);
    when(stateRepository.findById(nodeExecutionId)).thenReturn(Optional.of(aggState));
    when(stateRepository.findByIdForUpdate(nodeExecutionId)).thenReturn(Optional.of(aggState));
    when(voteRepository.findById(any())).thenReturn(Optional.empty());

    RoutingResult mockRouting = mock(RoutingResult.class);
    when(routingService.route(any(), any(), any())).thenReturn(mockRouting);

    // Approver 1 rejects
    when(actorProvider.requireActor())
        .thenReturn(new ActorContext(approver1, "user1", Set.of(), Set.of()));

    TaskCommandService.TaskDecisionResult result =
        commandService.decideTask(
            task1Id,
            BusinessOutcome.REJECTED,
            JsonNodeFactory.instance.objectNode(),
            "Budget exceeded",
            new CorrelationId(UUID.randomUUID()),
            new CommandId(UUID.randomUUID()));

    assertThat(result.task().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(result.task().getOutcome()).isEqualTo("REJECTED");
    assertThat(result.routingResult()).isNotNull(); // FAIL_FAST triggered node completion!
    assertThat(nodeExec.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(nodeExec.getOutcomePort()).isEqualTo("REJECTED");

    // Sibling tasks must be cancelled!
    assertThat(task2.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(task3.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    verify(slaActivationService).cancel(eq(task2Id), any());
    verify(slaActivationService).cancel(eq(task3Id), any());
  }

  @Test
  @DisplayName("SEQUENTIAL_TASKS exposes exactly the next assignee after each decision")
  void sequentialTasksCreatesOnlyTheNextTaskAndCarriesTheRemainingOrder() {
    UUID firstTaskId = UUID.randomUUID();
    TaskExecution firstTask = createTask(firstTaskId, nodeExecutionId, approver1);
    NodeExecution nodeExec = createNodeExecution(nodeExecutionId, eventId);
    TaskAggregationState aggState =
        TaskAggregationState.create(
            nodeExecutionId,
            3,
            new TaskAggregationPolicy(
                DecisionAggregationPolicy.ALL_APPROVE,
                null,
                null,
                RejectBehavior.WAIT_ALL,
                RemainingTaskBehavior.KEEP_ACTIVE));

    TaskCandidate second =
        TaskCandidate.create(
            firstTaskId,
            approver2,
            "SEQUENTIAL",
            JsonNodeFactory.instance.objectNode().put("orderIndex", 1),
            now);
    TaskCandidate third =
        TaskCandidate.create(
            firstTaskId,
            approver3,
            "SEQUENTIAL",
            JsonNodeFactory.instance.objectNode().put("orderIndex", 2),
            now);

    when(taskRepository.findById(firstTaskId)).thenReturn(Optional.of(firstTask));
    when(executionRepository.findById(nodeExecutionId)).thenReturn(Optional.of(nodeExec));
    when(stateRepository.existsById(nodeExecutionId)).thenReturn(true);
    when(stateRepository.findById(nodeExecutionId)).thenReturn(Optional.of(aggState));
    when(stateRepository.findByIdForUpdate(nodeExecutionId)).thenReturn(Optional.of(aggState));
    when(voteRepository.findById(any())).thenReturn(Optional.empty());
    when(candidateRepository.findAllByTaskIdOrderByCreatedAtAsc(firstTaskId))
        .thenReturn(List.of(third, second));
    when(actorProvider.requireActor())
        .thenReturn(new ActorContext(approver1, "user1", Set.of(), Set.of()));

    TaskCommandService.TaskDecisionResult result =
        commandService.decideTask(
            firstTaskId,
            BusinessOutcome.APPROVED,
            JsonNodeFactory.instance.objectNode(),
            "first approval",
            new CorrelationId(UUID.randomUUID()),
            new CommandId(UUID.randomUUID()));

    assertThat(result.routingResult()).isNull();
    assertThat(aggState.getCompletedTasks()).isEqualTo(1);

    org.mockito.ArgumentCaptor<TaskExecution> taskCaptor =
        org.mockito.ArgumentCaptor.forClass(TaskExecution.class);
    verify(taskRepository, times(2)).saveAndFlush(taskCaptor.capture());
    TaskExecution nextTask =
        taskCaptor.getAllValues().stream()
            .filter(saved -> !saved.getId().equals(firstTaskId))
            .findFirst()
            .orElseThrow();
    assertThat(nextTask.getAssigneeId()).isEqualTo(approver2);
    assertThat(nextTask.getStatus()).isEqualTo(TaskStatus.READY);

    org.mockito.ArgumentCaptor<TaskCandidate> remainingCaptor =
        org.mockito.ArgumentCaptor.forClass(TaskCandidate.class);
    verify(candidateRepository).save(remainingCaptor.capture());
    assertThat(remainingCaptor.getValue().getTaskId()).isEqualTo(nextTask.getId());
    assertThat(remainingCaptor.getValue().getUserId()).isEqualTo(approver3);
    assertThat(remainingCaptor.getValue().getSourceSnapshotJson().path("orderIndex").asInt())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("HumanTaskParticipantActivationHook activates multi-approver node into N tasks and TaskAggregationState")
  void multiApproverNodeActivationCreatesMultipleTasksAndAggregationState() {
    com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository snapshotRepo =
        mock(com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository.class);
    com.fpt.workflow.nodetype.NodeTypeRegistry registry =
        mock(com.fpt.workflow.nodetype.NodeTypeRegistry.class);
    com.fpt.workflow.organization.service.OrganizationHierarchyService hierarchy =
        mock(com.fpt.workflow.organization.service.OrganizationHierarchyService.class);
    com.fpt.workflow.resolver.participant.ParticipantResolverRegistry participantRegistry =
        mock(com.fpt.workflow.resolver.participant.ParticipantResolverRegistry.class);
    com.fpt.workflow.task.repository.TaskCandidateRepository candidateRepo =
        mock(com.fpt.workflow.task.repository.TaskCandidateRepository.class);

    com.fpt.workflow.nodetype.NodeTypeManifest manifest =
        mock(com.fpt.workflow.nodetype.NodeTypeManifest.class);
    when(manifest.supportedCapabilities())
        .thenReturn(Set.of(com.fpt.workflow.nodetype.NodeCapability.HUMAN_TASK));
    when(registry.require(any())).thenReturn(manifest);

    com.fpt.workflow.task.service.HumanTaskParticipantActivationHook hook =
        new com.fpt.workflow.task.service.HumanTaskParticipantActivationHook(
            taskRepository,
            snapshotRepo,
            registry,
            hierarchy,
            uuidGenerator,
            clock,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            slaActivationService,
            itemExecutionRepository,
            participantRegistry,
            stateRepository,
            candidateRepo);

    com.fasterxml.jackson.databind.node.ObjectNode config =
        JsonNodeFactory.instance.objectNode();
    com.fasterxml.jackson.databind.node.ArrayNode users = config.putObject("participant").putArray("users");
    users.add(approver1.toString());
    users.add(approver2.toString());
    config.putObject("task")
        .put("generationStrategy", "TASK_PER_USER")
        .put("decisionAggregationPolicy", "ALL_APPROVE")
        .put("rejectBehavior", "FAIL_FAST")
        .put("remainingTaskBehavior", "CANCEL_REMAINING");

    com.fpt.workflow.definition.domain.NodeDefinition node =
        com.fpt.workflow.definition.domain.NodeDefinition.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "approvalNode",
            "APPROVAL",
            "Approval Node",
            "Please review",
            1,
            config,
            null,
            null,
            JsonNodeFactory.instance.objectNode());

    Event event = mock(Event.class);
    when(event.getId()).thenReturn(eventId);
    when(event.getStartedBy()).thenReturn(UUID.randomUUID());

    NodeExecution nodeExec = createNodeExecution(nodeExecutionId, eventId);

    hook.onActivation(event, node, nodeExec, mock(com.fpt.workflow.runtime.context.EventContext.class));

    // Verify 2 tasks created and saved
    verify(taskRepository, times(2)).saveAndFlush(any(TaskExecution.class));

    // Verify TaskAggregationState created and saved
    org.mockito.ArgumentCaptor<TaskAggregationState> captor =
        org.mockito.ArgumentCaptor.forClass(TaskAggregationState.class);
    verify(stateRepository).saveAndFlush(captor.capture());
    TaskAggregationState captured = captor.getValue();
    assertThat(captured.getNodeExecutionId()).isEqualTo(nodeExecutionId);
    assertThat(captured.getTotalTasks()).isEqualTo(2);
    assertThat(captured.getDecisionPolicy()).isEqualTo(DecisionAggregationPolicy.ALL_APPROVE);
    assertThat(captured.getRejectBehavior()).isEqualTo(RejectBehavior.FAIL_FAST);
    assertThat(captured.getRemainingTaskBehavior()).isEqualTo(RemainingTaskBehavior.CANCEL_REMAINING);
  }

  private TaskExecution createTask(UUID id, UUID nodeId, UUID assignee) {
    return TaskExecution.create(
        id,
        nodeId,
        null,
        assignee,
        "Approval Task",
        "Please approve",
        null,
        JsonNodeFactory.instance.objectNode(),
        50,
        now.plusSeconds(3600),
        now);
  }

  private NodeExecution createNodeExecution(UUID id, UUID eventId) {
    NodeExecution exec =
        NodeExecution.create(
            id,
            eventId,
            UUID.randomUUID(),
            "approvalNode",
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
}
