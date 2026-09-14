package com.fpt.workflow.runtime.subworkflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.publish.WorkflowPublishService;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.validation.ValidationCompilation;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.lifecycle.EventLifecycleService;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowExecution;
import com.fpt.workflow.runtime.subworkflow.repository.SubWorkflowExecutionRepository;
import com.fpt.workflow.runtime.subworkflow.service.SubWorkflowService;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.ticket.service.TicketMutationBoundary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SubWorkflowExecutionIT {

  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UuidGenerator uuidGenerator;
  @Autowired private WorkflowDefinitionRepository definitionRepository;
  @Autowired private WorkflowVersionRepository versionRepository;
  @Autowired private NodeDefinitionRepository nodeRepository;
  @Autowired private EdgeDefinitionRepository edgeRepository;
  @Autowired private WorkflowValidationService validationService;
  @Autowired private WorkflowPublishService publishService;
  @Autowired private EventRepository eventRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private SubWorkflowExecutionRepository subWorkflowExecutionRepository;
  @Autowired private NodeActivationService activationService;
  @Autowired private RoutingService routingService;
  @Autowired private EventLifecycleService eventLifecycleService;
  @Autowired private SubWorkflowService subWorkflowService;

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testChildVersionPublishedBeforeActivation_resolvesLatestPublishedVersion() {
    String suffix = suffix();
    String childKey = "CHILD_WF_" + suffix;
    String parentKey = "PARENT_WF_" + suffix;

    // 1. Create Child Workflow with V1
    ChildWorkflowFixture childFixture = createAndPublishChildWorkflow(childKey, 1);
    UUID childV1Id = childFixture.versionId();

    // 2. Create Parent Workflow referencing childKey
    ParentWorkflowFixture parentFixture =
        createParentWorkflow(parentKey, childKey, "WAIT_FOR_COMPLETION", "PROPAGATE");

    // 3. Start Parent Event (child is at V1 right now)
    Event parentEvent = startEventForWorkflow(parentFixture.versionId());

    // 4. Publish Child V2 BEFORE SubWorkflow node is activated
    UUID childV2Id = publishNewChildVersion(childFixture.definitionId(), 2);

    // 5. Activate Parent START node and route to SUB_WORKFLOW node
    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                parentEvent.getId(), parentFixture.startNodeId(), UUID.randomUUID(), corr, cmd));
    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution subNodeExecution = startRoute.activations().get(0);

    // 6. Verify Critical Version Rule: Child event bound to V2 (not V1)!
    SubWorkflowExecution subExec =
        subWorkflowExecutionRepository
            .findByParentNodeExecutionId(subNodeExecution.getId())
            .orElseThrow();
    assertThat(subExec.getChildWorkflowVersionId()).isEqualTo(childV2Id);
    assertThat(subExec.getChildWorkflowVersionId()).isNotEqualTo(childV1Id);

    Event childEvent = eventRepository.findById(subExec.getChildEventId()).orElseThrow();
    assertThat(childEvent.getWorkflowVersionId()).isEqualTo(childV2Id);
    assertThat(childEvent.getRootEventId()).isEqualTo(parentEvent.getRootEventId());
    assertThat(childEvent.getParentEventId()).isEqualTo(parentEvent.getId());
    assertThat(childEvent.getParentNodeExecutionId()).isEqualTo(subNodeExecution.getId());
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testVersionPublishedAfterChildEventCreation_childVersionNeverChanges() {
    String suffix = suffix();
    String childKey = "CHILD_WF_" + suffix;
    String parentKey = "PARENT_WF_" + suffix;

    ChildWorkflowFixture childFixture = createAndPublishChildWorkflow(childKey, 1);
    ParentWorkflowFixture parentFixture =
        createParentWorkflow(parentKey, childKey, "WAIT_FOR_COMPLETION", "PROPAGATE");

    Event parentEvent = startEventForWorkflow(parentFixture.versionId());

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                parentEvent.getId(), parentFixture.startNodeId(), UUID.randomUUID(), corr, cmd));
    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution subNodeExecution = startRoute.activations().get(0);

    SubWorkflowExecution subExec =
        subWorkflowExecutionRepository
            .findByParentNodeExecutionId(subNodeExecution.getId())
            .orElseThrow();
    UUID originalChildVersionId = subExec.getChildWorkflowVersionId();

    // Publish Child V2 AFTER child event is already running
    publishNewChildVersion(childFixture.definitionId(), 2);

    // Verify: running child event still holds V1!
    Event childEvent = eventRepository.findById(subExec.getChildEventId()).orElseThrow();
    assertThat(childEvent.getWorkflowVersionId()).isEqualTo(originalChildVersionId);

    SubWorkflowExecution reloadedSubExec =
        subWorkflowExecutionRepository.findById(subExec.getId()).orElseThrow();
    assertThat(reloadedSubExec.getChildWorkflowVersionId()).isEqualTo(originalChildVersionId);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testWaitMode_parentWaitsAndResumesOnChildCompletion() {
    String suffix = suffix();
    String childKey = "CHILD_WF_" + suffix;
    String parentKey = "PARENT_WF_" + suffix;

    ChildWorkflowFixture childFixture = createAndPublishChildWorkflow(childKey, 1);
    ParentWorkflowFixture parentFixture =
        createParentWorkflow(parentKey, childKey, "WAIT_FOR_COMPLETION", "PROPAGATE");

    Event parentEvent = startEventForWorkflow(parentFixture.versionId());

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                parentEvent.getId(), parentFixture.startNodeId(), UUID.randomUUID(), corr, cmd));
    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution subNodeExecution = startRoute.activations().get(0);

    // Parent node is WAITING with CHILD_EVENT
    NodeExecution waitingSubNode =
        nodeExecutionRepository.findById(subNodeExecution.getId()).orElseThrow();
    assertThat(waitingSubNode.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);
    assertThat(waitingSubNode.getWaitReason()).isEqualTo(RuntimeWaitReason.CHILD_EVENT);

    SubWorkflowExecution subExec =
        subWorkflowExecutionRepository
            .findByParentNodeExecutionId(subNodeExecution.getId())
            .orElseThrow();
    Event childEvent = eventRepository.findById(subExec.getChildEventId()).orElseThrow();

    // Complete child workflow
    childEvent.complete("COMPLETED", Instant.now());
    eventRepository.saveAndFlush(childEvent);
    subWorkflowService.onChildEventTerminal(childEvent, corr, cmd);

    // Parent SubWorkflow node completed with COMPLETED outcomePort
    NodeExecution completedSubNode =
        nodeExecutionRepository.findById(subNodeExecution.getId()).orElseThrow();
    assertThat(completedSubNode.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(completedSubNode.getOutcomePort()).isEqualTo("COMPLETED");

    // Parent downstream END node activated
    List<NodeExecution> parentNodes =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(parentEvent.getId());
    assertThat(parentNodes)
        .anyMatch(n -> n.getNodeDefinitionId().equals(parentFixture.endNodeId()));
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testFireAndContinueMode_parentCompletesImmediatelyAndChildRuns() {
    String suffix = suffix();
    String childKey = "CHILD_WF_" + suffix;
    String parentKey = "PARENT_WF_" + suffix;

    createAndPublishChildWorkflow(childKey, 1);
    ParentWorkflowFixture parentFixture =
        createParentWorkflow(parentKey, childKey, "FIRE_AND_CONTINUE", "DETACH");

    Event parentEvent = startEventForWorkflow(parentFixture.versionId());

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                parentEvent.getId(), parentFixture.startNodeId(), UUID.randomUUID(), corr, cmd));
    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution subNodeExecution = startRoute.activations().get(0);

    // In FIRE_AND_CONTINUE: SubWorkflow node completes immediately!
    assertThat(subNodeExecution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(subNodeExecution.getOutcomePort()).isEqualTo("COMPLETED");

    // Route downstream immediately
    RoutingResult subRoute = routingService.route(subNodeExecution.getId(), corr, cmd);
    assertThat(subRoute.activations()).hasSize(1);
    assertThat(subRoute.activations().get(0).getNodeDefinitionId())
        .isEqualTo(parentFixture.endNodeId());

    // Child event is created and still RUNNING independently
    SubWorkflowExecution subExec =
        subWorkflowExecutionRepository
            .findByParentNodeExecutionId(subNodeExecution.getId())
            .orElseThrow();
    Event childEvent = eventRepository.findById(subExec.getChildEventId()).orElseThrow();
    assertThat(childEvent.getStatus()).isEqualTo(EventStatus.RUNNING);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testCancelPropagation_propagateCancelsChild() {
    String suffix = suffix();
    String childKey = "CHILD_WF_" + suffix;
    String parentKey = "PARENT_WF_" + suffix;

    createAndPublishChildWorkflow(childKey, 1);
    ParentWorkflowFixture parentFixture =
        createParentWorkflow(parentKey, childKey, "WAIT_FOR_COMPLETION", "PROPAGATE");

    Event parentEvent = startEventForWorkflow(parentFixture.versionId());

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                parentEvent.getId(), parentFixture.startNodeId(), UUID.randomUUID(), corr, cmd));
    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution subNodeExecution = startRoute.activations().get(0);

    SubWorkflowExecution subExec =
        subWorkflowExecutionRepository
            .findByParentNodeExecutionId(subNodeExecution.getId())
            .orElseThrow();

    // Cancel parent event
    eventLifecycleService.cancelEvent(parentEvent.getId(), cmd, corr, "Test cancel");

    // Parent node is CANCELLED
    NodeExecution cancelledParentNode =
        nodeExecutionRepository.findById(subNodeExecution.getId()).orElseThrow();
    assertThat(cancelledParentNode.getStatus()).isEqualTo(NodeExecutionStatus.CANCELLED);

    // Child event is CANCELLED (propagated!)
    Event childEvent = eventRepository.findById(subExec.getChildEventId()).orElseThrow();
    assertThat(childEvent.getStatus()).isEqualTo(EventStatus.CANCELLED);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testCancelPropagation_detachKeepsChildRunning() {
    String suffix = suffix();
    String childKey = "CHILD_WF_" + suffix;
    String parentKey = "PARENT_WF_" + suffix;

    createAndPublishChildWorkflow(childKey, 1);
    ParentWorkflowFixture parentFixture =
        createParentWorkflow(parentKey, childKey, "WAIT_FOR_COMPLETION", "DETACH");

    Event parentEvent = startEventForWorkflow(parentFixture.versionId());

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                parentEvent.getId(), parentFixture.startNodeId(), UUID.randomUUID(), corr, cmd));
    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution subNodeExecution = startRoute.activations().get(0);

    SubWorkflowExecution subExec =
        subWorkflowExecutionRepository
            .findByParentNodeExecutionId(subNodeExecution.getId())
            .orElseThrow();

    // Cancel parent event
    eventLifecycleService.cancelEvent(parentEvent.getId(), cmd, corr, "Test cancel with detach");

    // Parent node is CANCELLED
    NodeExecution cancelledParentNode =
        nodeExecutionRepository.findById(subNodeExecution.getId()).orElseThrow();
    assertThat(cancelledParentNode.getStatus()).isEqualTo(NodeExecutionStatus.CANCELLED);

    // Child event is NOT cancelled (detached!)
    Event childEvent = eventRepository.findById(subExec.getChildEventId()).orElseThrow();
    assertThat(childEvent.getStatus()).isEqualTo(EventStatus.RUNNING);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testDirectRecursion_blockedByPublishValidator() {
    String suffix = suffix();
    String key = "DIRECT_REC_" + suffix;

    // Workflow A has SubWorkflow referencing Workflow A
    ParentWorkflowFixture fixture =
        createParentWorkflow(key, key, "WAIT_FOR_COMPLETION", "PROPAGATE");

    ValidationCompilation compilation = validationService.compileCurrent(fixture.versionId());
    assertThat(compilation.valid()).isFalse();
    assertThat(compilation.publishable()).isFalse();
    assertThat(compilation.issues())
        .anyMatch(issue -> "SUBWORKFLOW_RECURSION_DETECTED".equals(issue.code()));
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testIndirectRecursion_blockedByPublishValidator() {
    String suffix = suffix();
    String keyA = "INDIRECT_A_" + suffix;
    String keyB = "INDIRECT_B_" + suffix;

    // 1. Workflow B created with SubWorkflow referencing A
    ParentWorkflowFixture fixtureB =
        createParentWorkflow(keyB, keyA, "WAIT_FOR_COMPLETION", "PROPAGATE");

    // 2. Workflow A created with SubWorkflow referencing B
    ParentWorkflowFixture fixtureA =
        createParentWorkflow(keyA, keyB, "WAIT_FOR_COMPLETION", "PROPAGATE");

    // Compiling A detects indirect cycle A -> B -> A
    ValidationCompilation compilationA = validationService.compileCurrent(fixtureA.versionId());
    assertThat(compilationA.valid()).isFalse();
    assertThat(compilationA.publishable()).isFalse();
    assertThat(compilationA.issues())
        .anyMatch(
            issue ->
                "SUBWORKFLOW_RECURSION_DETECTED".equals(issue.code())
                    && issue.message().contains("Indirect sub-workflow recursion cycle detected"));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // P1-24: Per-node sub-workflow child failure strategies (§16.1 / §16.2)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testChildNoPublishedVersion_routesFailedPort() {
    // Given: parent workflow with ROUTE_FAILED strategy and a FAILED edge → error END
    String suffix = suffix();
    String childKey = "NO_PUB_FAIL_" + suffix;
    String parentKey = "PARENT_NO_PUB_" + suffix;

    // Create child definition WITHOUT any published version
    UUID childDefId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Unpublished Child', 'ACTIVE', ?, ?, now(), now())",
        childDefId, childKey, ACTOR_ID, ACTOR_ID);
    // No PUBLISHED version, no current_published_version_id

    // Parent with ROUTE_FAILED (default) strategy + FAILED edge to error END
    ParentWorkflowWithFailureFixture parentFixture =
        createParentWorkflowWithFailedPort(parentKey, childKey, "ROUTE_FAILED");
    Event parentEvent = startEventForWorkflow(parentFixture.versionId());

    // When: activate START → route to SUB_WORKFLOW node
    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                parentEvent.getId(), parentFixture.startNodeId(), UUID.randomUUID(), corr, cmd));
    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution subNodeExecution = startRoute.activations().get(0);

    // Then: SubWorkflow node completes with outcomePort=FAILED (not throws)
    assertThat(subNodeExecution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(subNodeExecution.getOutcomePort()).isEqualTo("FAILED");

    // And: routing through FAILED port reaches the error END node
    RoutingResult failRoute = routingService.route(subNodeExecution.getId(), corr, cmd);
    assertThat(failRoute.activations()).hasSize(1);
    assertThat(failRoute.activations().get(0).getNodeDefinitionId())
        .isEqualTo(parentFixture.errorEndNodeId());
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testChildNoPublishedVersion_failParentStrategy() {
    // Given: parent workflow with FAIL_PARENT strategy
    String suffix = suffix();
    String childKey = "NO_PUB_FP_" + suffix;
    String parentKey = "PARENT_FP_" + suffix;

    UUID childDefId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Unpublished Child', 'ACTIVE', ?, ?, now(), now())",
        childDefId, childKey, ACTOR_ID, ACTOR_ID);

    ParentWorkflowWithFailureFixture parentFixture =
        createParentWorkflowWithFailedPort(parentKey, childKey, "FAIL_PARENT");
    Event parentEvent = startEventForWorkflow(parentFixture.versionId());

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                parentEvent.getId(), parentFixture.startNodeId(), UUID.randomUUID(), corr, cmd));
    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution subNodeExecution = startRoute.activations().get(0);

    // Then: SubWorkflow node is FAILED (not COMPLETED/FAILED port)
    assertThat(subNodeExecution.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);

    // And: parent event is FAILED
    eventLifecycleService.syncEventStatus(parentEvent.getId());
    Event reloadedParentEvent = eventRepository.findById(parentEvent.getId()).orElseThrow();
    assertThat(reloadedParentEvent.getStatus()).isEqualTo(EventStatus.FAILED);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testChildRuntimeFailure_routesFailedPort() {
    // Given: child workflow that runs but then FAILS at runtime
    String suffix = suffix();
    String childKey = "CHILD_RTF_" + suffix;
    String parentKey = "PARENT_RTF_" + suffix;

    createAndPublishChildWorkflow(childKey, 1);
    ParentWorkflowWithFailureFixture parentFixture =
        createParentWorkflowWithFailedPort(parentKey, childKey, "ROUTE_FAILED");
    Event parentEvent = startEventForWorkflow(parentFixture.versionId());

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                parentEvent.getId(), parentFixture.startNodeId(), UUID.randomUUID(), corr, cmd));
    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution subNodeExecution = startRoute.activations().get(0);

    // Parent node is WAITING for child
    assertThat(subNodeExecution.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);

    SubWorkflowExecution subExec =
        subWorkflowExecutionRepository
            .findByParentNodeExecutionId(subNodeExecution.getId())
            .orElseThrow();
    Event childEvent = eventRepository.findById(subExec.getChildEventId()).orElseThrow();

    // Simulate child runtime FAILURE
    childEvent.fail(java.time.Instant.now());
    eventRepository.saveAndFlush(childEvent);
    subWorkflowService.onChildEventTerminal(childEvent, corr, cmd);

    // Then: parent SubWorkflow node completes with FAILED outcomePort
    NodeExecution completedSubNode =
        nodeExecutionRepository.findById(subNodeExecution.getId()).orElseThrow();
    assertThat(completedSubNode.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(completedSubNode.getOutcomePort()).isEqualTo("FAILED");

    // And: routes to error END node via FAILED port
    List<NodeExecution> allNodes =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(parentEvent.getId());
    assertThat(allNodes)
        .anyMatch(n -> n.getNodeDefinitionId().equals(parentFixture.errorEndNodeId()));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // P1-25: Ticket mutation isolation boundary (§16.5 / Appendix E §11.5)
  // ────────────────────────────────────────────────────────────────────────────

  @Autowired private TicketMutationBoundary ticketMutationBoundary;

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testTicketMutationIsolation_childContextBlocksMutationOnParentTicket() {
    // Given: two arbitrary UUIDs — requireCanMutateTicket(UUID) is ThreadLocal-only,
    // no DB lookup is required for this code path.
    UUID parentTicketId = UUID.randomUUID();
    UUID childEventId = UUID.randomUUID();

    // When: inside a child workflow context referencing parentTicketId
    try (var ignored = ticketMutationBoundary.enterChildWorkflow(childEventId, parentTicketId)) {
      // Then: requireCanMutateTicket throws CHILD_WORKFLOW_TICKET_MUTATION_FORBIDDEN
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> ticketMutationBoundary.requireCanMutateTicket(parentTicketId))
          .isInstanceOf(com.fpt.workflow.shared.api.CommandConflictException.class)
          .satisfies(
              ex -> {
                com.fpt.workflow.shared.api.CommandConflictException cce =
                    (com.fpt.workflow.shared.api.CommandConflictException) ex;
                assertThat(cce.code()).isEqualTo("CHILD_WORKFLOW_TICKET_MUTATION_FORBIDDEN");
              });
    }
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testTicketMutationIsolation_explicitPermissionScopeAllowsMutation() {
    // Given: same child workflow context
    UUID parentTicketId = UUID.randomUUID();
    UUID childEventId = UUID.randomUUID();

    try (var childScope = ticketMutationBoundary.enterChildWorkflow(childEventId, parentTicketId)) {
      // Inside explicit permission scope: no exception should be thrown
      try (var permScope = ticketMutationBoundary.openExplicitPermissionScope()) {
        org.assertj.core.api.Assertions.assertThatNoException()
            .isThrownBy(() -> ticketMutationBoundary.requireCanMutateTicket(parentTicketId));
      }
      // After explicit scope closes, the block is restored
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> ticketMutationBoundary.requireCanMutateTicket(parentTicketId))
          .isInstanceOf(com.fpt.workflow.shared.api.CommandConflictException.class);
    }

    // After child scope closes: no restriction on mutations at all
    org.assertj.core.api.Assertions.assertThatNoException()
        .isThrownBy(() -> ticketMutationBoundary.requireCanMutateTicket(parentTicketId));
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void testTicketMutationIsolation_nonParentTicketNotBlocked() {
    // Given: child context on parentTicketId
    UUID parentTicketId = UUID.randomUUID();
    UUID otherTicketId = UUID.randomUUID();
    UUID childEventId = UUID.randomUUID();

    try (var ignored = ticketMutationBoundary.enterChildWorkflow(childEventId, parentTicketId)) {
      // Mutating a DIFFERENT ticket is allowed
      org.assertj.core.api.Assertions.assertThatNoException()
          .isThrownBy(() -> ticketMutationBoundary.requireCanMutateTicket(otherTicketId));
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Fixture helpers
  // ────────────────────────────────────────────────────────────────────────────

  private String suffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
  }

  private record ChildWorkflowFixture(
      UUID definitionId, UUID versionId, UUID startId, UUID endId) {}

  private ChildWorkflowFixture createAndPublishChildWorkflow(String key, int versionNo) {
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID endId = UUID.randomUUID();
    UUID edgeId = UUID.randomUUID();

    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Child Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        key,
        ACTOR_ID,
        ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, ?, 'DRAFT', 0, ?, now())",
        verId,
        defId,
        versionNo,
        ACTOR_ID);

    ObjectNode startConfig = objectMapper.createObjectNode();
    startConfig.put("routingMode", "ALL_OUTGOING");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start', 1, ?::jsonb, '{}'::jsonb)",
        startId,
        verId,
        startConfig.toString());

    ObjectNode endConfig = objectMapper.createObjectNode();
    endConfig.put("outcome", "CHILD_COMPLETED");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'end', 'END', 'End', 1, ?::jsonb, '{}'::jsonb)",
        endId,
        verId,
        endConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeId,
        verId,
        startId,
        endId);

    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() WHERE id = ?",
        ACTOR_ID,
        verId);

    jdbcTemplate.update(
        "UPDATE workflow_definitions SET current_published_version_id = ? WHERE id = ?",
        verId,
        defId);

    return new ChildWorkflowFixture(defId, verId, startId, endId);
  }

  private UUID publishNewChildVersion(UUID definitionId, int versionNo) {
    UUID newVerId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID endId = UUID.randomUUID();
    UUID edgeId = UUID.randomUUID();

    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, ?, 'DRAFT', 0, ?, now())",
        newVerId,
        definitionId,
        versionNo,
        ACTOR_ID);

    ObjectNode startConfig = objectMapper.createObjectNode();
    startConfig.put("routingMode", "ALL_OUTGOING");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start', 1, ?::jsonb, '{}'::jsonb)",
        startId,
        newVerId,
        startConfig.toString());

    ObjectNode endConfig = objectMapper.createObjectNode();
    endConfig.put("outcome", "CHILD_V" + versionNo + "_COMPLETED");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'end', 'END', 'End', 1, ?::jsonb, '{}'::jsonb)",
        endId,
        newVerId,
        endConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeId,
        newVerId,
        startId,
        endId);

    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'SUPERSEDED' WHERE definition_id = ? AND status = 'PUBLISHED'",
        definitionId);

    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() WHERE id = ?",
        ACTOR_ID,
        newVerId);

    jdbcTemplate.update(
        "UPDATE workflow_definitions SET current_published_version_id = ? WHERE id = ?",
        newVerId,
        definitionId);

    return newVerId;
  }

  private record ParentWorkflowFixture(
      UUID definitionId, UUID versionId, UUID startNodeId, UUID subNodeId, UUID endNodeId) {}

  private ParentWorkflowFixture createParentWorkflow(
      String parentKey, String childKey, String executionMode, String cancellationPolicy) {
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    UUID endId = UUID.randomUUID();
    UUID edge1 = UUID.randomUUID();
    UUID edge2 = UUID.randomUUID();

    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Parent Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        parentKey,
        ACTOR_ID,
        ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        verId,
        defId,
        ACTOR_ID);

    ObjectNode startConfig = objectMapper.createObjectNode();
    startConfig.put("routingMode", "ALL_OUTGOING");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start', 1, ?::jsonb, '{}'::jsonb)",
        startId,
        verId,
        startConfig.toString());

    ObjectNode subConfig = objectMapper.createObjectNode();
    subConfig.put("childWorkflowDefinitionKey", childKey);
    subConfig.put("executionMode", executionMode);
    subConfig.put("cancellationPolicy", cancellationPolicy);
    subConfig.put("routingMode", "SINGLE_BY_PORT");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'sub_workflow', 'SUB_WORKFLOW', 'Sub Workflow Node', 1, ?::jsonb, '{}'::jsonb)",
        subId,
        verId,
        subConfig.toString());

    ObjectNode endConfig = objectMapper.createObjectNode();
    endConfig.put("outcome", "PARENT_COMPLETED");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'end', 'END', 'Parent End', 1, ?::jsonb, '{}'::jsonb)",
        endId,
        verId,
        endConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edge1,
        verId,
        startId,
        subId);

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'COMPLETED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edge2,
        verId,
        subId,
        endId);

    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() WHERE id = ?",
        ACTOR_ID,
        verId);

    jdbcTemplate.update(
        "UPDATE workflow_definitions SET current_published_version_id = ? WHERE id = ?",
        verId,
        defId);

    return new ParentWorkflowFixture(defId, verId, startId, subId, endId);
  }

  private Event startEventForWorkflow(UUID versionId) {
    WorkflowVersion version = versionRepository.findById(versionId).orElseThrow();
    UUID defId = version.getDefinitionId();
    String suffix = suffix();
    UUID reqTypeId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID revId = UUID.randomUUID();

    jdbcTemplate.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Req', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        reqTypeId,
        "req-" + suffix,
        defId);

    jdbcTemplate.update(
        "INSERT INTO tickets (id, request_type_id, creator_id, status, data_json, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, now(), now())",
        ticketId,
        reqTypeId,
        ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id, ticket_id, revision_no, data_snapshot_json, source_schema_version, schema_checksum, submitted_by, submitted_at) "
            + "VALUES (?, ?, 1, '{}'::jsonb, 'v1', 'checksum', ?, now())",
        revId,
        ticketId,
        ACTOR_ID);

    jdbcTemplate.update(
        "UPDATE tickets SET status = 'SUBMITTED', data_revision = 1, current_revision_id = ?, submitted_at = now(), updated_at = now() WHERE id = ?",
        revId,
        ticketId);

    Event ev =
        Event.createRoot(
            uuidGenerator.generate(),
            ticketId,
            versionId,
            revId,
            null,
            null,
            "USER_SUBMIT",
            "corr-" + suffix,
            objectMapper.createObjectNode(),
            ACTOR_ID,
            Instant.now());
    ev.markRunning();
    return eventRepository.save(ev);
  }

  private record ParentWorkflowWithFailureFixture(
      UUID definitionId,
      UUID versionId,
      UUID startNodeId,
      UUID subNodeId,
      UUID endNodeId,
      UUID errorEndNodeId) {}

  /**
   * Creates a parent workflow with:
   * <ul>
   *   <li>START → SUB_WORKFLOW (with configured failureStrategy)
   *   <li>SUB_WORKFLOW[COMPLETED] → success END
   *   <li>SUB_WORKFLOW[FAILED] → error END
   * </ul>
   */
  private ParentWorkflowWithFailureFixture createParentWorkflowWithFailedPort(
      String parentKey, String childKey, String failureStrategy) {
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    UUID endId = UUID.randomUUID();
    UUID errorEndId = UUID.randomUUID();
    UUID edge1 = UUID.randomUUID();
    UUID edge2 = UUID.randomUUID();
    UUID edge3 = UUID.randomUUID();

    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Parent Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId, parentKey, ACTOR_ID, ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        verId, defId, ACTOR_ID);

    ObjectNode startConfig = objectMapper.createObjectNode();
    startConfig.put("routingMode", "ALL_OUTGOING");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start', 1, ?::jsonb, '{}'::jsonb)",
        startId, verId, startConfig.toString());

    ObjectNode subConfig = objectMapper.createObjectNode();
    subConfig.put("childWorkflowDefinitionKey", childKey);
    subConfig.put("executionMode", "WAIT_FOR_COMPLETION");
    subConfig.put("cancellationPolicy", "PROPAGATE");
    subConfig.put("failureStrategy", failureStrategy);
    subConfig.put("routingMode", "SINGLE_BY_PORT");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'sub_workflow', 'SUB_WORKFLOW', 'Sub Workflow Node', 1, ?::jsonb, '{}'::jsonb)",
        subId, verId, subConfig.toString());

    ObjectNode endConfig = objectMapper.createObjectNode();
    endConfig.put("outcome", "PARENT_COMPLETED");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'end', 'END', 'Success End', 1, ?::jsonb, '{}'::jsonb)",
        endId, verId, endConfig.toString());

    ObjectNode errorEndConfig = objectMapper.createObjectNode();
    errorEndConfig.put("outcome", "PARENT_FAILED");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'error_end', 'END', 'Error End', 1, ?::jsonb, '{}'::jsonb)",
        errorEndId, verId, errorEndConfig.toString());

    // START → SUB_WORKFLOW
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edge1, verId, startId, subId);

    // SUB_WORKFLOW[COMPLETED] → success END
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'COMPLETED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edge2, verId, subId, endId);

    // SUB_WORKFLOW[FAILED] → error END
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'FAILED', ?, 0, false, 'CONDITIONAL', '{}'::jsonb)",
        edge3, verId, subId, errorEndId);

    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() WHERE id = ?",
        ACTOR_ID, verId);

    jdbcTemplate.update(
        "UPDATE workflow_definitions SET current_published_version_id = ? WHERE id = ?",
        verId, defId);

    return new ParentWorkflowWithFailureFixture(defId, verId, startId, subId, endId, errorEndId);
  }
}
