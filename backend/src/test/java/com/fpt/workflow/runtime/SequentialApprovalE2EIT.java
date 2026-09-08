package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowValidationRun;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.publish.WorkflowPublishService;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.engine.FieldEditability;
import com.fpt.workflow.form.engine.FieldRequirement;
import com.fpt.workflow.form.engine.FieldSemanticMetadata;
import com.fpt.workflow.form.engine.FieldValidationRules;
import com.fpt.workflow.form.engine.FieldVisibility;
import com.fpt.workflow.form.engine.FormFieldDefinition;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.execution.WorkflowExecutionService;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.AuthenticatedActorPrincipal;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import com.fpt.workflow.task.domain.TaskDecision;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskDecisionRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.task.service.TaskCommandService;
import com.fpt.workflow.ticket.dto.TicketDtos;
import com.fpt.workflow.ticket.service.TicketService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SequentialApprovalE2EIT {

  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_approval_e2e_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowDefinitionRepository definitionRepository;
  @Autowired private WorkflowVersionRepository versionRepository;
  @Autowired private NodeDefinitionRepository nodeRepository;
  @Autowired private EdgeDefinitionRepository edgeRepository;
  @Autowired private WorkflowFormRepository formRepository;
  @Autowired private WorkflowValidationService validationService;
  @Autowired private WorkflowPublishService publishService;
  @Autowired private TicketService ticketService;
  @Autowired private EventRepository eventRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private TaskExecutionRepository taskExecutionRepository;
  @Autowired private TaskDecisionRepository taskDecisionRepository;
  @Autowired private ParticipantSnapshotRepository participantSnapshotRepository;
  @Autowired private WorkflowExecutionService workflowExecutionService;
  @Autowired private TaskCommandService taskCommandService;
  @Autowired private AuditEventRepository auditRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @WithMockActor(roles = {"WORKFLOW_OWNER", "ADMIN", "USER"})
  void sequentialApproval_e2e_approvalPath_completesWithApprovedOutcome() {
    CanonicalWorkflowSetup setup = setupCanonicalWorkflow();

    // 1. Submit Ticket against RequestType form
    ObjectNode dataJson = objectMapper.createObjectNode().put("amount", 250);
    TicketDtos.CreateDraft draftReq =
        new TicketDtos.CreateDraft(setup.requestTypeId(), dataJson, List.of());
    TicketDtos.AggregateView ticketDraft = ticketService.createDraft(draftReq);
    UUID ticketId = ticketDraft.ticket().id();

    TicketDtos.Submit submitReq =
        new TicketDtos.Submit(
            setup.publishedVersionId(), setup.schemaChecksum(), "Travel expense request", 0);
    TicketDtos.AggregateView submittedTicket =
        ticketService.submit(
            ticketId,
            new ExpectedVersion(ticketDraft.ticket().lockVersion()),
            submitReq,
            new CommandId(UUID.randomUUID()));
    assertThat(submittedTicket.ticket().status()).isEqualTo(TicketStatus.SUBMITTED);

    // 2. Verify Event bound to exact published version
    List<Event> events = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId);
    assertThat(events).hasSize(1);
    Event event = events.getFirst();
    assertThat(event.getWorkflowVersionId()).isEqualTo(setup.publishedVersionId());
    assertThat(event.getStatus()).isEqualTo(EventStatus.CREATED);

    // 3. Activate START node -> routes automatically via EdgeDefinition to APPROVAL
    CorrelationId correlationId = new CorrelationId(UUID.randomUUID());
    CommandId startCommand = new CommandId(UUID.randomUUID());
    UUID cycleId = UUID.randomUUID();

    WorkflowExecutionService.StartEventResult startResult =
        workflowExecutionService.startEvent(event.getId(), cycleId, correlationId, startCommand);
    assertThat(startResult.rootExecution().getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(startResult.rootExecution().getOutcomePort()).isEqualTo("STARTED");

    // 4. Verify Event entered WAITING status and Approval Task created in READY status
    Event waitingEvent = eventRepository.findById(event.getId()).orElseThrow();
    assertThat(waitingEvent.getStatus()).isEqualTo(EventStatus.WAITING);
    assertThat(waitingEvent.getWaitReason()).isEqualTo(RuntimeWaitReason.HUMAN_TASK);

    List<NodeExecution> executions =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId());
    assertThat(executions).hasSize(2);
    NodeExecution approvalExecution = executions.get(1);
    assertThat(approvalExecution.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);
    assertThat(approvalExecution.getWaitReason()).isEqualTo(RuntimeWaitReason.HUMAN_TASK);

    List<ParticipantSnapshot> snapshots =
        participantSnapshotRepository.findAllByNodeExecutionIdOrderByResolvedAtAsc(
            approvalExecution.getId());
    assertThat(snapshots).hasSize(1);
    assertThat(snapshots.getFirst().getResolutionStatus())
        .isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(snapshots.getFirst().getParticipantRole()).isEqualTo("APPROVER");

    List<TaskExecution> tasks =
        taskExecutionRepository.findAllByNodeExecutionIdOrderByCreatedAtAsc(
            approvalExecution.getId());
    assertThat(tasks).hasSize(1);
    TaskExecution task = tasks.getFirst();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.READY);
    assertThat(task.getTitleSnapshot()).isEqualTo("Manager Approval");

    // 5. Execute APPROVE decision via TaskCommandService
    CommandId decideCommand = new CommandId(UUID.randomUUID());
    ObjectNode approvalForm = objectMapper.createObjectNode().put("approvedBudget", 250);
    TaskCommandService.TaskDecisionResult decisionResult =
        taskCommandService.decideTask(
            task.getId(),
            BusinessOutcome.of("APPROVED"),
            approvalForm,
            "Approved as requested",
            correlationId,
            decideCommand);

    assertThat(decisionResult.task().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(decisionResult.task().getOutcome()).isEqualTo("APPROVED");

    // 6. Verify TaskDecision recorded
    Optional<TaskDecision> decisionOpt = taskDecisionRepository.findByTaskId(task.getId());
    assertThat(decisionOpt).isPresent();
    assertThat(decisionOpt.get().getOutcome()).isEqualTo("APPROVED");
    assertThat(decisionOpt.get().getComment()).isEqualTo("Approved as requested");

    // 7. Verify downstream routing to END_APPROVED and Event completion
    Event completedEvent = eventRepository.findById(event.getId()).orElseThrow();
    assertThat(completedEvent.getStatus()).isEqualTo(EventStatus.COMPLETED);
    assertThat(completedEvent.getOutcome()).isEqualTo("APPROVED");

    List<NodeExecution> finalExecutions =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId());
    assertThat(finalExecutions).hasSize(3); // START, APPROVAL, END_APPROVED
    NodeExecution endExecution = finalExecutions.get(2);
    assertThat(endExecution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(endExecution.getNodeDefinitionId()).isEqualTo(setup.endApprovedNode().getId());

    // 8. Verify complete audit trail across the entire path
    List<AuditEvent> audits =
        auditRepository.findAllByCorrelationIdOrderByOccurredAtAsc(correlationId.value());
    assertThat(audits)
        .extracting(AuditEvent::getEventType)
        .contains("NODE_ACTIVATED", "NODE_ROUTED", "TASK_DECIDED");
  }

  @Test
  @WithMockActor(roles = {"WORKFLOW_OWNER", "ADMIN", "USER"})
  void sequentialApproval_e2e_rejectionPath_completesWithRejectedOutcome() {
    CanonicalWorkflowSetup setup = setupCanonicalWorkflow();

    // 1. Submit Ticket
    ObjectNode dataJson = objectMapper.createObjectNode().put("amount", 9000);
    TicketDtos.CreateDraft draftReq =
        new TicketDtos.CreateDraft(setup.requestTypeId(), dataJson, List.of());
    TicketDtos.AggregateView ticketDraft = ticketService.createDraft(draftReq);
    UUID ticketId = ticketDraft.ticket().id();

    TicketDtos.Submit submitReq =
        new TicketDtos.Submit(
            setup.publishedVersionId(), setup.schemaChecksum(), "Expensive equipment", 0);
    ticketService.submit(
        ticketId,
        new ExpectedVersion(ticketDraft.ticket().lockVersion()),
        submitReq,
        new CommandId(UUID.randomUUID()));

    Event event = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId).getFirst();

    // 2. Start Event -> routes to APPROVAL
    CorrelationId correlationId = new CorrelationId(UUID.randomUUID());
    CommandId startCommand = new CommandId(UUID.randomUUID());
    UUID cycleId = UUID.randomUUID();
    workflowExecutionService.startEvent(event.getId(), cycleId, correlationId, startCommand);

    NodeExecution approvalExecution =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId()).stream()
            .filter(e -> e.getStatus() == NodeExecutionStatus.WAITING)
            .findFirst()
            .orElseThrow();

    TaskExecution task =
        taskExecutionRepository
            .findAllByNodeExecutionIdOrderByCreatedAtAsc(approvalExecution.getId())
            .getFirst();

    // 3. Execute REJECT decision
    CommandId rejectCommand = new CommandId(UUID.randomUUID());
    TaskCommandService.TaskDecisionResult decisionResult =
        taskCommandService.decideTask(
            task.getId(),
            BusinessOutcome.of("REJECTED"),
            objectMapper.createObjectNode(),
            "Budget exceeded limit",
            correlationId,
            rejectCommand);

    assertThat(decisionResult.task().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(decisionResult.task().getOutcome()).isEqualTo("REJECTED");

    // 4. Verify Event completed with REJECTED outcome
    Event completedEvent = eventRepository.findById(event.getId()).orElseThrow();
    assertThat(completedEvent.getStatus()).isEqualTo(EventStatus.COMPLETED);
    assertThat(completedEvent.getOutcome()).isEqualTo("REJECTED");

    List<NodeExecution> finalExecutions =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId());
    assertThat(finalExecutions).hasSize(3); // START, APPROVAL, END_REJECTED
    NodeExecution endExecution = finalExecutions.get(2);
    assertThat(endExecution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(endExecution.getNodeDefinitionId()).isEqualTo(setup.endRejectedNode().getId());
  }

  @Test
  @WithMockActor(roles = {"WORKFLOW_OWNER", "ADMIN", "USER"})
  void sequentialApproval_e2e_concurrentDecide_enforcesSingleWinnerAndRejectsLateCommand()
      throws Exception {
    CanonicalWorkflowSetup setup = setupCanonicalWorkflow();

    // Submit Ticket & start Event
    ObjectNode dataJson = objectMapper.createObjectNode().put("amount", 100);
    TicketDtos.CreateDraft draftReq =
        new TicketDtos.CreateDraft(setup.requestTypeId(), dataJson, List.of());
    TicketDtos.AggregateView ticketDraft = ticketService.createDraft(draftReq);
    UUID ticketId = ticketDraft.ticket().id();

    ticketService.submit(
        ticketId,
        new ExpectedVersion(ticketDraft.ticket().lockVersion()),
        new TicketDtos.Submit(setup.publishedVersionId(), setup.schemaChecksum(), "Test", 0),
        new CommandId(UUID.randomUUID()));

    Event event = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId).getFirst();
    CorrelationId correlationId = new CorrelationId(UUID.randomUUID());
    workflowExecutionService.startEvent(
        event.getId(), UUID.randomUUID(), correlationId, new CommandId(UUID.randomUUID()));

    NodeExecution approvalExecution =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId()).stream()
            .filter(e -> e.getStatus() == NodeExecutionStatus.WAITING)
            .findFirst()
            .orElseThrow();

    TaskExecution task =
        taskExecutionRepository
            .findAllByNodeExecutionIdOrderByCreatedAtAsc(approvalExecution.getId())
            .getFirst();

    // Attempt concurrent decisions
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch readyGate = new CountDownLatch(2);
    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger(0);
    AtomicInteger failures = new AtomicInteger(0);

    org.springframework.security.core.context.SecurityContext securityContext =
        org.springframework.security.core.context.SecurityContextHolder.getContext();
    Runnable decideAction =
        () -> {
          org.springframework.security.core.context.SecurityContextHolder.setContext(
              securityContext);
          readyGate.countDown();
          try {
            startGate.await(5, TimeUnit.SECONDS);
            taskCommandService.decideTask(
                task.getId(),
                BusinessOutcome.of("APPROVED"),
                objectMapper.createObjectNode(),
                "Concurrent decision",
                correlationId,
                new CommandId(UUID.randomUUID()));
            successes.incrementAndGet();
          } catch (Exception expected) {
            failures.incrementAndGet();
          } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
          }
        };

    Future<?> f1 = pool.submit(decideAction);
    Future<?> f2 = pool.submit(decideAction);

    readyGate.await(5, TimeUnit.SECONDS);
    startGate.countDown();

    f1.get(10, TimeUnit.SECONDS);
    f2.get(10, TimeUnit.SECONDS);
    pool.shutdown();

    // Exactly one decision must succeed; the other must be rejected
    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(1);

    // Any subsequent late command must be rejected with terminal state exception
    assertThatThrownBy(
            () ->
                taskCommandService.decideTask(
                    task.getId(),
                    BusinessOutcome.of("APPROVED"),
                    objectMapper.createObjectNode(),
                    "Late command",
                    correlationId,
                    new CommandId(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Task is already terminal");
  }

  @Test
  @WithMockActor(roles = {"WORKFLOW_OWNER", "ADMIN", "USER"})
  void sequentialApproval_e2e_unauthorizedActor_rejectedWithAccessDenied() {
    CanonicalWorkflowSetup setup = setupCanonicalWorkflow();

    // 1. Submit Ticket
    ObjectNode dataJson = objectMapper.createObjectNode().put("amount", 2000);
    TicketDtos.CreateDraft draftReq =
        new TicketDtos.CreateDraft(setup.requestTypeId(), dataJson, List.of());
    TicketDtos.AggregateView ticketDraft = ticketService.createDraft(draftReq);
    UUID ticketId = ticketDraft.ticket().id();

    TicketDtos.Submit submitReq =
        new TicketDtos.Submit(
            setup.publishedVersionId(), setup.schemaChecksum(), "Office supplies", 0);
    ticketService.submit(
        ticketId,
        new ExpectedVersion(ticketDraft.ticket().lockVersion()),
        submitReq,
        new CommandId(UUID.randomUUID()));

    Event event = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId).getFirst();

    // 2. Start Event -> routes to APPROVAL
    CorrelationId correlationId = new CorrelationId(UUID.randomUUID());
    CommandId startCommand = new CommandId(UUID.randomUUID());
    UUID cycleId = UUID.randomUUID();
    workflowExecutionService.startEvent(event.getId(), cycleId, correlationId, startCommand);

    NodeExecution approvalExecution =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId()).stream()
            .filter(ne -> ne.getNodeDefinitionId().equals(setup.approvalNode().getId()))
            .findFirst()
            .orElseThrow();

    TaskExecution task =
        taskExecutionRepository
            .findAllByNodeExecutionIdOrderByCreatedAtAsc(approvalExecution.getId())
            .getFirst();

    // 3. Switch security context to an unauthorized actor
    UUID unauthorizedActorId = UUID.fromString("99999999-9999-4000-8000-999999999999");
    var unauthorizedPrincipal =
        new AuthenticatedActorPrincipal(unauthorizedActorId, "unauthorized@example.test");
    var unauthorizedAuth =
        UsernamePasswordAuthenticationToken.authenticated(
            unauthorizedPrincipal, "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContext prevContext = SecurityContextHolder.getContext();
    try {
      SecurityContext newContext = SecurityContextHolder.createEmptyContext();
      newContext.setAuthentication(unauthorizedAuth);
      SecurityContextHolder.setContext(newContext);

      // 4. Attempt to decide task -> must fail with AccessDeniedException
      assertThatThrownBy(
              () ->
                  taskCommandService.decideTask(
                      task.getId(),
                      BusinessOutcome.of("APPROVED"),
                      objectMapper.createObjectNode(),
                      "Malicious decision",
                      correlationId,
                      new CommandId(UUID.randomUUID())))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
          .hasMessageContaining("not authorized to decide task");
    } finally {
      SecurityContextHolder.setContext(prevContext);
    }

    // 5. Verify task remains READY and untouched
    TaskExecution refreshed = taskExecutionRepository.findById(task.getId()).orElseThrow();
    assertThat(refreshed.getStatus()).isEqualTo(TaskStatus.READY);
    assertThat(refreshed.getOutcome()).isNull();
  }

  private CanonicalWorkflowSetup setupCanonicalWorkflow() {
    Instant now = Instant.now();
    String suffix = UUID.randomUUID().toString().replace("-", "");

    // 1. Create WorkflowDefinition
    WorkflowDefinition definition =
        definitionRepository.save(
            WorkflowDefinition.create(
                UUID.randomUUID(),
                "canonical_approval_" + suffix,
                "Sequential Approval",
                "Canonical 3-node approval workflow",
                ACTOR_ID,
                ACTOR_ID,
                now));

    // 2. Create Draft WorkflowVersion
    WorkflowVersion draftVersion =
        versionRepository.save(
            WorkflowVersion.createDraft(
                UUID.randomUUID(), definition.getId(), 1, null, null, ACTOR_ID, now));
    definition.assignActiveDraft(draftVersion.getId(), now);
    definition = definitionRepository.save(definition);

    // 3. Create Nodes: START, APPROVAL, END_APPROVED, END_REJECTED
    NodeDefinition startNode =
        nodeRepository.save(
            NodeDefinition.create(
                UUID.randomUUID(),
                draftVersion.getId(),
                "start",
                "START",
                "Start",
                "Start node",
                1,
                objectMapper.createObjectNode(),
                null,
                null,
                objectMapper.createObjectNode().put("x", 0).put("y", 0)));

    ObjectNode approvalConfig = objectMapper.createObjectNode();
    ObjectNode participant = approvalConfig.putObject("participant");
    participant.put("type", "MANAGER_OF");
    ArrayNode actions = approvalConfig.putArray("allowedActions");
    actions.add("APPROVE");
    actions.add("REJECT");

    NodeDefinition approvalNode =
        nodeRepository.save(
            NodeDefinition.create(
                UUID.randomUUID(),
                draftVersion.getId(),
                "approval",
                "APPROVAL",
                "Manager Approval",
                "Approval task",
                1,
                approvalConfig,
                null,
                null,
                objectMapper.createObjectNode().put("x", 100).put("y", 0)));

    ObjectNode endApprovedConfig = objectMapper.createObjectNode();
    endApprovedConfig.put("outcome", "APPROVED");
    NodeDefinition endApprovedNode =
        nodeRepository.save(
            NodeDefinition.create(
                UUID.randomUUID(),
                draftVersion.getId(),
                "end_approved",
                "END",
                "Approved End",
                "Approved outcome terminal node",
                1,
                endApprovedConfig,
                null,
                null,
                objectMapper.createObjectNode().put("x", 200).put("y", -50)));

    ObjectNode endRejectedConfig = objectMapper.createObjectNode();
    endRejectedConfig.put("outcome", "REJECTED");
    NodeDefinition endRejectedNode =
        nodeRepository.save(
            NodeDefinition.create(
                UUID.randomUUID(),
                draftVersion.getId(),
                "end_rejected",
                "END",
                "Rejected End",
                "Rejected outcome terminal node",
                1,
                endRejectedConfig,
                null,
                null,
                objectMapper.createObjectNode().put("x", 200).put("y", 50)));

    // 4. Create Edges
    // START -> APPROVAL (port STARTED)
    edgeRepository.save(
        EdgeDefinition.create(
            UUID.randomUUID(),
            draftVersion.getId(),
            startNode.getId(),
            "STARTED",
            approvalNode.getId(),
            null,
            0,
            false,
            TransitionType.CONDITIONAL,
            null,
            objectMapper.createObjectNode()));

    // APPROVAL -> END_APPROVED (port APPROVED)
    edgeRepository.save(
        EdgeDefinition.create(
            UUID.randomUUID(),
            draftVersion.getId(),
            approvalNode.getId(),
            "APPROVED",
            endApprovedNode.getId(),
            null,
            0,
            false,
            TransitionType.CONDITIONAL,
            null,
            objectMapper.createObjectNode()));

    // APPROVAL -> END_REJECTED (port REJECTED)
    edgeRepository.save(
        EdgeDefinition.create(
            UUID.randomUUID(),
            draftVersion.getId(),
            approvalNode.getId(),
            "REJECTED",
            endRejectedNode.getId(),
            null,
            0,
            false,
            TransitionType.CONDITIONAL,
            null,
            objectMapper.createObjectNode()));

    // APPROVAL -> END_REJECTED (port REVISION_REQUESTED to satisfy compiler exhaustiveness)
    edgeRepository.save(
        EdgeDefinition.create(
            UUID.randomUUID(),
            draftVersion.getId(),
            approvalNode.getId(),
            "REVISION_REQUESTED",
            endRejectedNode.getId(),
            null,
            0,
            false,
            TransitionType.CONDITIONAL,
            null,
            objectMapper.createObjectNode()));

    // 5. Create Ticket Form
    FormFieldDefinition formField =
        new FormFieldDefinition(
            UUID.randomUUID(),
            "amount",
            "Expense Amount",
            null,
            null,
            0,
            TypeDescriptor.required(CanonicalValueType.INTEGER),
            null,
            false,
            FieldRequirement.always(),
            FieldVisibility.always(),
            FieldEditability.editable(),
            FieldValidationRules.none(),
            null,
            new FieldSemanticMetadata(false, false, false, false, false));
    FormSchema formSchema =
        new FormSchema("ticket", WorkflowFormType.TICKET_FORM, List.of(formField));
    String schemaChecksum = "checksum-" + suffix;
    formRepository.save(
        WorkflowForm.create(
            UUID.randomUUID(),
            draftVersion.getId(),
            "ticket",
            WorkflowFormType.TICKET_FORM,
            objectMapper.valueToTree(formSchema),
            schemaChecksum));

    // 6. Validate Draft Version
    WorkflowValidationService.PersistedValidation validation =
        validationService.validate(draftVersion.getId());
    WorkflowValidationRun run = validation.run();
    assertThat(run.isValid()).isTrue();
    assertThat(run.isPublishable()).isTrue();
    assertThat(run.getErrorCount()).isZero();

    // 7. Publish Draft Version
    WorkflowPublishService.PublishResult publishResult =
        publishService.publish(
            draftVersion.getId(),
            new ExpectedVersion(draftVersion.getLockVersion()),
            draftVersion.getRevision(),
            new CommandId(UUID.randomUUID()));
    assertThat(publishResult.status()).isEqualTo(WorkflowVersionStatus.PUBLISHED);

    // 8. Create RequestType bound to WorkflowDefinition
    UUID requestTypeId = UUID.randomUUID();
    String requestTypeKey = "req_" + suffix;
    jdbcTemplate.update(
        "INSERT INTO request_types (id,key,name,category,workflow_definition_id,active,creation_policy_json,created_at,updated_at) VALUES (?,?,'Expense Request','GENERAL',?,true,'{}'::jsonb,now(),now())",
        requestTypeId,
        requestTypeKey,
        definition.getId());

    return new CanonicalWorkflowSetup(
        definition,
        publishResult.workflowVersionId(),
        startNode,
        approvalNode,
        endApprovedNode,
        endRejectedNode,
        requestTypeId,
        requestTypeKey,
        schemaChecksum);
  }

  private record CanonicalWorkflowSetup(
      WorkflowDefinition definition,
      UUID publishedVersionId,
      NodeDefinition startNode,
      NodeDefinition approvalNode,
      NodeDefinition endApprovedNode,
      NodeDefinition endRejectedNode,
      UUID requestTypeId,
      String requestTypeKey,
      String schemaChecksum) {}
}
