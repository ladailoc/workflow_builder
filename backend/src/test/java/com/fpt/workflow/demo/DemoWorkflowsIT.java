package com.fpt.workflow.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.validation.ValidationCompilation;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.integration.client.DefaultConnectorActionClient;
import com.fpt.workflow.integration.client.IntegrationCallResponse;
import com.fpt.workflow.integration.service.SystemActionExecutionService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.execution.WorkflowExecutionService;
import com.fpt.workflow.runtime.join.service.JoinService;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowExecution;
import com.fpt.workflow.runtime.subworkflow.repository.SubWorkflowExecutionRepository;
import com.fpt.workflow.runtime.subworkflow.service.SubWorkflowService;
import com.fpt.workflow.security.AuthenticatedActorPrincipal;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.task.service.TaskCommandService;
import com.fpt.workflow.ticket.dto.TicketDtos;
import com.fpt.workflow.ticket.service.TicketService;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class DemoWorkflowsIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("demo_workflows_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private DemoWorkflowSeeder seeder;
  @Autowired private WorkflowDefinitionRepository definitionRepository;
  @Autowired private WorkflowVersionRepository versionRepository;
  @Autowired private WorkflowValidationService validationService;
  @Autowired private RequestTypeRepository requestTypeRepository;
  @Autowired private TicketService ticketService;
  @Autowired private WorkflowExecutionService workflowExecutionService;
  @Autowired private TaskExecutionRepository taskExecutionRepository;
  @Autowired private TaskCommandService taskCommandService;
  @Autowired private EventRepository eventRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private DefaultConnectorActionClient actionClient;
  @Autowired private SystemActionExecutionService systemActionExecutionService;
  @Autowired private SubWorkflowExecutionRepository subWorkflowExecutionRepository;
  @Autowired private SubWorkflowService subWorkflowService;
  @Autowired private RoutingService routingService;
  @Autowired private JoinService joinService;
  @Autowired private UuidGenerator uuidGenerator;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void setup() {
    seeder.seedAll();
  }

  @AfterEach
  void tearDown() {
    actionClient.clearTestDelegate();
  }

  @Test
  void seedAll_isIdempotentAndDeterministic() {
    // Calling seedAll a second time must execute with no errors, no duplicate keys, no exceptions
    seeder.seedAll();

    List<String> keys =
        List.of(
            DemoIdentities.KEY_VENDOR_VERIFICATION,
            DemoIdentities.KEY_LEAVE_REQUEST,
            DemoIdentities.KEY_ACCESS_REQUEST,
            DemoIdentities.KEY_PURCHASE_REQUEST,
            DemoIdentities.KEY_EMPLOYEE_EVALUATION);

    for (String key : keys) {
      WorkflowDefinition def = definitionRepository.findByKey(key).orElseThrow();
      assertThat(def.getCurrentPublishedVersionId()).isNotNull();
    }
  }

  @Test
  void allFiveWorkflowsValidateWithZeroErrors() {
    List<String> keys =
        List.of(
            DemoIdentities.KEY_VENDOR_VERIFICATION,
            DemoIdentities.KEY_LEAVE_REQUEST,
            DemoIdentities.KEY_ACCESS_REQUEST,
            DemoIdentities.KEY_PURCHASE_REQUEST,
            DemoIdentities.KEY_EMPLOYEE_EVALUATION);

    for (String key : keys) {
      WorkflowDefinition def = definitionRepository.findByKey(key).orElseThrow();
      UUID publishedId = def.getCurrentPublishedVersionId();
      assertThat(publishedId).as("Published version for " + key).isNotNull();

      ValidationCompilation compilation = validationService.compileCurrent(publishedId);
      assertThat(compilation.valid()).as("Valid: " + key + " issues=" + compilation.issues()).isTrue();
      assertThat(compilation.publishable()).as("Publishable: " + key + " issues=" + compilation.issues()).isTrue();
      assertThat(
              compilation.issues().stream()
                  .filter(
                      i ->
                          i.severity()
                              == com.fpt.workflow.definition.domain.ValidationSeverity.ERROR)
                  .toList())
          .as("Zero ERROR issues in " + key)
          .isEmpty();
    }
  }

  @Test
  void requestTypesAreBoundToPublishedWorkflowDefinitions() {
    List<String> reqKeys =
        List.of(
            DemoIdentities.REQ_LEAVE_REQUEST,
            DemoIdentities.REQ_ACCESS_REQUEST,
            DemoIdentities.REQ_PURCHASE_REQUEST,
            DemoIdentities.REQ_EMPLOYEE_EVALUATION);

    for (String reqKey : reqKeys) {
      RequestType rt = requestTypeRepository.findByKey(reqKey).orElseThrow();
      assertThat(rt.isActive()).isTrue();
      WorkflowDefinition def =
          definitionRepository.findById(rt.getWorkflowDefinitionId()).orElseThrow();
      assertThat(def.getCurrentPublishedVersionId()).isNotNull();
    }
  }

  @Test
  void databaseImmutabilityTrigger_preventsModifyingPublishedWorkflowVersion() {
    WorkflowDefinition def =
        definitionRepository.findByKey(DemoIdentities.KEY_LEAVE_REQUEST).orElseThrow();
    UUID versionId = def.getCurrentPublishedVersionId();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE workflow_versions SET checksum = 'tampered_checksum' WHERE id = ?",
                    versionId))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("immutable");
  }

  @Test
  void architectureInvariants_zeroBusinessSpecificLogicInEngineRuntime() {
    // Audit that runtime, nodetype, task, and ticket packages do NOT contain
    // workflow/business-specific class names
    List<String> forbiddenTerms =
        List.of(
            "LeaveRequest",
            "AccessRequest",
            "PurchaseRequest",
            "EmployeeEvaluation",
            "VendorVerification");

    File baseDir = new File("src/main/java/com/fpt/workflow");
    File[] subDirs =
        baseDir.listFiles(
            f ->
                f.isDirectory()
                    && !f.getName().equals("demo")
                    && !f.getName().equals("shared")
                    && !f.getName().equals("operations"));

    if (subDirs != null) {
      for (File dir : subDirs) {
        assertNoBusinessNamesRecursively(dir, forbiddenTerms);
      }
    }
  }

  private void assertNoBusinessNamesRecursively(File dir, List<String> forbidden) {
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File file : files) {
      if (file.isDirectory()) {
        assertNoBusinessNamesRecursively(file, forbidden);
      } else if (file.getName().endsWith(".java")) {
        for (String term : forbidden) {
          assertThat(file.getName())
              .as("Engine runtime class %s must not contain business term %s", file.getName(), term)
              .doesNotContain(term);
        }
      }
    }
  }

  @Test
  @WithMockActor(roles = {"WORKFLOW_OWNER", "ADMIN", "USER"})
  void smokeExecution_leaveRequest_approvesAndCompletes() {
    RequestType rt =
        requestTypeRepository.findByKey(DemoIdentities.REQ_LEAVE_REQUEST).orElseThrow();
    WorkflowDefinition def =
        definitionRepository.findById(rt.getWorkflowDefinitionId()).orElseThrow();
    UUID versionId = def.getCurrentPublishedVersionId();

    // 1. Submit Ticket by Employee A
    ObjectNode data = objectMapper.createObjectNode();
    data.put("leaveType", "ANNUAL");
    data.put("startDate", "2026-10-01");
    data.put("endDate", "2026-10-05");
    data.put("reason", "Family vacation");

    TicketDtos.AggregateView draft =
        asActor(
            DemoIdentities.EMPLOYEE_A_USER_ID,
            () ->
                ticketService.createDraft(new TicketDtos.CreateDraft(rt.getId(), data, List.of())));

    TicketDtos.AggregateView submitted =
        asActor(
            DemoIdentities.EMPLOYEE_A_USER_ID,
            () ->
                ticketService.submit(
                    draft.ticket().id(),
                    new ExpectedVersion(draft.ticket().lockVersion()),
                    new TicketDtos.Submit(versionId, "leave-form-v1", "Annual leave", 0),
                    new CommandId(UUID.randomUUID())));

    // 2. Start Event
    Event event =
        eventRepository.findAllByTicketIdOrderByStartedAtAsc(draft.ticket().id()).getFirst();
    CorrelationId corr = new CorrelationId(UUID.randomUUID());
    CommandId cmd = new CommandId(UUID.randomUUID());

    workflowExecutionService.startEvent(event.getId(), UUID.randomUUID(), corr, cmd);

    // 3. Manager Approval task created, assigned to Manager via MANAGER_OF(depth 1)
    List<TaskExecution> tasks = findTasksForEvent(event.getId());
    assertThat(tasks).hasSize(1);
    TaskExecution managerTask = tasks.getFirst();
    assertThat(managerTask.getStatus()).isEqualTo(TaskStatus.READY);
    assertThat(managerTask.getAssigneeId()).isEqualTo(DemoIdentities.MANAGER_USER_ID);

    // 4. Manager decides task APPROVED
    asActor(
        DemoIdentities.MANAGER_USER_ID,
        () ->
            taskCommandService.decideTask(
                managerTask.getId(),
                BusinessOutcome.of("APPROVED"),
                objectMapper.createObjectNode(),
                "Approved. Enjoy your vacation!",
                corr,
                new CommandId(UUID.randomUUID())));

    // 5. Downstream notification created -> routes to end_approved -> Event COMPLETED
    Event completedEvent = eventRepository.findById(event.getId()).orElseThrow();
    assertThat(completedEvent.getStatus()).isEqualTo(EventStatus.COMPLETED);
    assertThat(completedEvent.getOutcome()).isEqualTo("APPROVED");
  }

  @Test
  @WithMockActor(roles = {"WORKFLOW_OWNER", "ADMIN", "USER"})
  void smokeExecution_accessRequest_managerAndSecurityApproveAndProvisionCompletes() {
    RequestType rt =
        requestTypeRepository.findByKey(DemoIdentities.REQ_ACCESS_REQUEST).orElseThrow();
    WorkflowDefinition def =
        definitionRepository.findById(rt.getWorkflowDefinitionId()).orElseThrow();
    UUID versionId = def.getCurrentPublishedVersionId();

    // 1. Submit Ticket by Employee B
    ObjectNode data = objectMapper.createObjectNode();
    data.put("systemName", "InternalDatabase");
    data.put("accessLevel", "READ_ONLY");
    data.put("justification", "Project migration audit");

    TicketDtos.AggregateView draft =
        asActor(
            DemoIdentities.EMPLOYEE_B_USER_ID,
            () ->
                ticketService.createDraft(new TicketDtos.CreateDraft(rt.getId(), data, List.of())));

    asActor(
        DemoIdentities.EMPLOYEE_B_USER_ID,
        () ->
            ticketService.submit(
                draft.ticket().id(),
                new ExpectedVersion(draft.ticket().lockVersion()),
                new TicketDtos.Submit(versionId, "access-form-v1", "DB Access", 0),
                new CommandId(UUID.randomUUID())));

    Event event =
        eventRepository.findAllByTicketIdOrderByStartedAtAsc(draft.ticket().id()).getFirst();
    CorrelationId corr = new CorrelationId(UUID.randomUUID());
    CommandId cmd = new CommandId(UUID.randomUUID());

    // 2. Start Event -> routes to Manager Approval
    workflowExecutionService.startEvent(event.getId(), UUID.randomUUID(), corr, cmd);
    TaskExecution mgrTask = findTasksForEvent(event.getId()).getFirst();
    assertThat(mgrTask.getAssigneeId()).isEqualTo(DemoIdentities.MANAGER_USER_ID);

    // 3. Manager decides APPROVED -> routes to Security Review
    asActor(
        DemoIdentities.MANAGER_USER_ID,
        () ->
            taskCommandService.decideTask(
                mgrTask.getId(),
                BusinessOutcome.of("APPROVED"),
                objectMapper.createObjectNode(),
                "Approved by manager",
                corr,
                new CommandId(UUID.randomUUID())));

    List<TaskExecution> updatedTasks = findTasksForEvent(event.getId());
    assertThat(updatedTasks).hasSize(2);
    TaskExecution secTask = updatedTasks.get(1);
    assertThat(secTask.getAssigneeId()).isEqualTo(DemoIdentities.SECURITY_USER_ID);

    // 4. Security officer decides SUBMITTED -> routes to provision_access SystemAction
    asActor(
        DemoIdentities.SECURITY_USER_ID,
        () ->
            taskCommandService.decideTask(
                secTask.getId(),
                BusinessOutcome.of("SUBMITTED"),
                objectMapper.createObjectNode(),
                "Security cleared",
                corr,
                new CommandId(UUID.randomUUID())));

    // 5. System Action stub executes successfully -> routes to end_completed
    NodeExecution actionExec =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId()).stream()
            .filter(ne -> "provision_access".equals(getNodeKey(ne)))
            .findFirst()
            .orElseThrow();

    actionClient.setTestDelegate(
        req -> {
          ObjectNode resp = objectMapper.createObjectNode();
          resp.put("status", "SUCCESS");
          return IntegrationCallResponse.success(200, resp);
        });

    systemActionExecutionService.execute(
        actionExec.getId(), corr, new CommandId(UUID.randomUUID()));

    Event completedEvent = eventRepository.findById(event.getId()).orElseThrow();
    assertThat(completedEvent.getStatus()).isEqualTo(EventStatus.COMPLETED);
    assertThat(completedEvent.getOutcome()).isEqualTo("COMPLETED");
  }

  @Test
  @WithMockActor(roles = {"WORKFLOW_OWNER", "ADMIN", "USER"})
  void smokeExecution_purchaseRequest_subworkflowAndParallelJoinCompletes() {
    RequestType rt =
        requestTypeRepository.findByKey(DemoIdentities.REQ_PURCHASE_REQUEST).orElseThrow();
    WorkflowDefinition def =
        definitionRepository.findById(rt.getWorkflowDefinitionId()).orElseThrow();
    UUID versionId = def.getCurrentPublishedVersionId();

    // 1. Submit Ticket by Employee C
    ObjectNode data = objectMapper.createObjectNode();
    data.put("vendorName", "CloudTech Corp");
    data.put("amount", 8500);
    data.put("category", "INFRASTRUCTURE");
    data.put("description", "Annual cloud compute subscription");

    TicketDtos.AggregateView draft =
        asActor(
            DemoIdentities.EMPLOYEE_C_USER_ID,
            () ->
                ticketService.createDraft(new TicketDtos.CreateDraft(rt.getId(), data, List.of())));

    asActor(
        DemoIdentities.EMPLOYEE_C_USER_ID,
        () ->
            ticketService.submit(
                draft.ticket().id(),
                new ExpectedVersion(draft.ticket().lockVersion()),
                new TicketDtos.Submit(versionId, "purchase-form-v1", "Cloud purchase", 0),
                new CommandId(UUID.randomUUID())));

    Event event =
        eventRepository.findAllByTicketIdOrderByStartedAtAsc(draft.ticket().id()).getFirst();
    CorrelationId corr = new CorrelationId(UUID.randomUUID());
    CommandId cmd = new CommandId(UUID.randomUUID());

    // 2. Start Event -> routes to Manager Approval
    workflowExecutionService.startEvent(event.getId(), UUID.randomUUID(), corr, cmd);
    TaskExecution mgrTask = findTasksForEvent(event.getId()).getFirst();

    // 3. Manager decides APPROVED -> routes to vendor_subworkflow
    asActor(
        DemoIdentities.MANAGER_USER_ID,
        () ->
            taskCommandService.decideTask(
                mgrTask.getId(),
                BusinessOutcome.of("APPROVED"),
                objectMapper.createObjectNode(),
                "Approved purchase",
                corr,
                new CommandId(UUID.randomUUID())));

    NodeExecution subwfExec =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId()).stream()
            .filter(ne -> "vendor_subworkflow".equals(getNodeKey(ne)))
            .findFirst()
            .orElseThrow();
    assertThat(subwfExec.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);

    SubWorkflowExecution subExecRecord =
        subWorkflowExecutionRepository.findByParentNodeExecutionId(subwfExec.getId()).orElseThrow();
    Event childEvent = eventRepository.findById(subExecRecord.getChildEventId()).orElseThrow();

    // 4. Complete Child Subworkflow event -> resumes parent SubWorkflow node
    childEvent.complete("COMPLETED", Instant.now());
    eventRepository.saveAndFlush(childEvent);
    subWorkflowService.onChildEventTerminal(childEvent, corr, cmd);

    // 5. Parent SubWorkflow node completed -> routes through parallel_split to finance_approval and
    // legal_review
    List<TaskExecution> parallelTasks =
        findTasksForEvent(event.getId()).stream()
            .filter(t -> t.getStatus() == TaskStatus.READY)
            .toList();
    assertThat(parallelTasks).hasSize(2);

    TaskExecution finTask =
        parallelTasks.stream()
            .filter(t -> DemoIdentities.FINANCE_USER_ID.equals(t.getAssigneeId()))
            .findFirst()
            .orElseThrow();
    TaskExecution legTask =
        parallelTasks.stream()
            .filter(t -> DemoIdentities.LEGAL_USER_ID.equals(t.getAssigneeId()))
            .findFirst()
            .orElseThrow();

    // 6. Finance Lead approves
    asActor(
        DemoIdentities.FINANCE_USER_ID,
        () ->
            taskCommandService.decideTask(
                finTask.getId(),
                BusinessOutcome.of("APPROVED"),
                objectMapper.createObjectNode(),
                "Finance budget verified",
                corr,
                new CommandId(UUID.randomUUID())));

    // 7. Legal Lead submits review -> join_all threshold met -> routes to erp_create_po
    asActor(
        DemoIdentities.LEGAL_USER_ID,
        () ->
            taskCommandService.decideTask(
                legTask.getId(),
                BusinessOutcome.of("SUBMITTED"),
                objectMapper.createObjectNode(),
                "Legal contracts cleared",
                corr,
                new CommandId(UUID.randomUUID())));

    // 8. ERP SystemAction executes
    NodeExecution erpExec =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId()).stream()
            .filter(ne -> "erp_create_po".equals(getNodeKey(ne)))
            .findFirst()
            .orElseThrow();

    actionClient.setTestDelegate(
        req -> {
          ObjectNode resp = objectMapper.createObjectNode();
          resp.put("poNumber", "PO-2026-9901");
          return IntegrationCallResponse.success(200, resp);
        });

    systemActionExecutionService.execute(erpExec.getId(), corr, new CommandId(UUID.randomUUID()));

    Event completedEvent = eventRepository.findById(event.getId()).orElseThrow();
    assertThat(completedEvent.getStatus()).isEqualTo(EventStatus.COMPLETED);
    assertThat(completedEvent.getOutcome()).isEqualTo("COMPLETED");
  }

  @Test
  @WithMockActor(roles = {"WORKFLOW_OWNER", "ADMIN", "USER"})
  void smokeExecution_employeeEvaluation_multiInstanceAndReworkLoop() {
    RequestType rt =
        requestTypeRepository.findByKey(DemoIdentities.REQ_EMPLOYEE_EVALUATION).orElseThrow();
    WorkflowDefinition def =
        definitionRepository.findById(rt.getWorkflowDefinitionId()).orElseThrow();
    UUID versionId = def.getCurrentPublishedVersionId();

    // 1. Submit Ticket by Manager with 2 targets: Employee A & Employee B
    ObjectNode data = objectMapper.createObjectNode();
    data.put("period", "2026-Q3");
    ArrayNode targets = data.putArray("evaluationTargets");
    targets.add(DemoIdentities.EMPLOYEE_A_USER_ID.toString());
    targets.add(DemoIdentities.EMPLOYEE_B_USER_ID.toString());

    TicketDtos.AggregateView draft =
        asActor(
            DemoIdentities.MANAGER_USER_ID,
            () ->
                ticketService.createDraft(new TicketDtos.CreateDraft(rt.getId(), data, List.of())));

    asActor(
        DemoIdentities.MANAGER_USER_ID,
        () ->
            ticketService.submit(
                draft.ticket().id(),
                new ExpectedVersion(draft.ticket().lockVersion()),
                new TicketDtos.Submit(versionId, "evaluation-form-v1", "Q3 Evaluation", 0),
                new CommandId(UUID.randomUUID())));

    Event event =
        eventRepository.findAllByTicketIdOrderByStartedAtAsc(draft.ticket().id()).getFirst();
    CorrelationId corr = new CorrelationId(UUID.randomUUID());
    CommandId cmd = new CommandId(UUID.randomUUID());

    // 2. Start Event -> routes to self_evaluation (Multi-Instance)
    workflowExecutionService.startEvent(event.getId(), UUID.randomUUID(), corr, cmd);

    List<TaskExecution> selfTasks =
        findTasksForEvent(event.getId()).stream()
            .filter(t -> t.getStatus() == TaskStatus.READY)
            .toList();
    assertThat(selfTasks).hasSize(2);
    assertThat(selfTasks)
        .extracting(TaskExecution::getAssigneeId)
        .containsExactlyInAnyOrder(
            DemoIdentities.EMPLOYEE_A_USER_ID, DemoIdentities.EMPLOYEE_B_USER_ID);

    // 3. Both employees decide SUBMITTED on their self evaluation
    for (TaskExecution selfTask : selfTasks) {
      asActor(
          selfTask.getAssigneeId(),
          () ->
              taskCommandService.decideTask(
                  selfTask.getId(),
                  BusinessOutcome.of("SUBMITTED"),
                  objectMapper.createObjectNode(),
                  "Self evaluation completed",
                  corr,
                  new CommandId(UUID.randomUUID())));
    }

    // 4. self_evaluation completes -> routes to manager_review (Multi-Instance)
    List<TaskExecution> mgrReviewTasks =
        findTasksForEvent(event.getId()).stream()
            .filter(t -> t.getStatus() == TaskStatus.READY)
            .toList();
    assertThat(mgrReviewTasks).hasSize(2);
    assertThat(mgrReviewTasks)
        .allMatch(t -> DemoIdentities.MANAGER_USER_ID.equals(t.getAssigneeId()));

    // 5. Manager decides SUBMITTED on both employee reviews
    for (TaskExecution mgrTask : mgrReviewTasks) {
      asActor(
          DemoIdentities.MANAGER_USER_ID,
          () ->
              taskCommandService.decideTask(
                  mgrTask.getId(),
                  BusinessOutcome.of("SUBMITTED"),
                  objectMapper.createObjectNode(),
                  "Manager evaluation completed",
                  corr,
                  new CommandId(UUID.randomUUID())));
    }

    // 6. Routes to higher_manager_approval -> assigned to Director via MANAGER_OF(depth 1 from
    // Manager)
    TaskExecution higherTask =
        findTasksForEvent(event.getId()).stream()
            .filter(t -> t.getStatus() == TaskStatus.READY)
            .findFirst()
            .orElseThrow();
    assertThat(higherTask.getAssigneeId()).isEqualTo(DemoIdentities.DIRECTOR_USER_ID);

    // 7. Director REJECTS -> Bounded rework edge triggers!
    // Creates a new manager_review occurrence (iteration 1) without mutating completed history.
    asActor(
        DemoIdentities.DIRECTOR_USER_ID,
        () ->
            taskCommandService.decideTask(
                higherTask.getId(),
                BusinessOutcome.of("REJECTED"),
                objectMapper.createObjectNode(),
                "Please provide more quantitative KPIs",
                corr,
                new CommandId(UUID.randomUUID())));

    // Verify: higherTask is completed with REJECTED
    TaskExecution finishedHigher =
        taskExecutionRepository.findById(higherTask.getId()).orElseThrow();
    assertThat(finishedHigher.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(finishedHigher.getOutcome()).isEqualTo("REJECTED");

    // Verify: new manager review tasks created for iteration 1!
    List<TaskExecution> reworkTasks =
        findTasksForEvent(event.getId()).stream()
            .filter(t -> t.getStatus() == TaskStatus.READY)
            .toList();
    assertThat(reworkTasks).isNotEmpty();
    assertThat(reworkTasks).allMatch(t -> DemoIdentities.MANAGER_USER_ID.equals(t.getAssigneeId()));

    // 8. Manager decides SUBMITTED on reworked tasks
    for (TaskExecution reworkTask : reworkTasks) {
      asActor(
          DemoIdentities.MANAGER_USER_ID,
          () ->
              taskCommandService.decideTask(
                  reworkTask.getId(),
                  BusinessOutcome.of("SUBMITTED"),
                  objectMapper.createObjectNode(),
                  "Updated with KPIs",
                  corr,
                  new CommandId(UUID.randomUUID())));
    }

    // 9. Back at higher_manager_approval -> Director decides APPROVED
    TaskExecution secondHigherTask =
        findTasksForEvent(event.getId()).stream()
            .filter(t -> t.getStatus() == TaskStatus.READY)
            .findFirst()
            .orElseThrow();
    assertThat(secondHigherTask.getAssigneeId()).isEqualTo(DemoIdentities.DIRECTOR_USER_ID);

    asActor(
        DemoIdentities.DIRECTOR_USER_ID,
        () ->
            taskCommandService.decideTask(
                secondHigherTask.getId(),
                BusinessOutcome.of("APPROVED"),
                objectMapper.createObjectNode(),
                "Approved with updated KPIs",
                corr,
                new CommandId(UUID.randomUUID())));

    // 10. Routes to hr_review -> assigned to HR lead
    TaskExecution hrTask =
        findTasksForEvent(event.getId()).stream()
            .filter(t -> t.getStatus() == TaskStatus.READY)
            .findFirst()
            .orElseThrow();
    assertThat(hrTask.getAssigneeId()).isEqualTo(DemoIdentities.HR_USER_ID);

    asActor(
        DemoIdentities.HR_USER_ID,
        () ->
            taskCommandService.decideTask(
                hrTask.getId(),
                BusinessOutcome.of("SUBMITTED"),
                objectMapper.createObjectNode(),
                "HR signoff complete",
                corr,
                new CommandId(UUID.randomUUID())));

    // 11. Event reaches end_completed!
    Event completedEvent = eventRepository.findById(event.getId()).orElseThrow();
    assertThat(completedEvent.getStatus()).isEqualTo(EventStatus.COMPLETED);
    assertThat(completedEvent.getOutcome()).isEqualTo("COMPLETED");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Test Helpers
  // ────────────────────────────────────────────────────────────────────────────
  private List<TaskExecution> findTasksForEvent(UUID eventId) {
    List<UUID> nodeExecIds =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(eventId).stream()
            .map(NodeExecution::getId)
            .toList();
    if (nodeExecIds.isEmpty()) {
      return List.of();
    }
    return taskExecutionRepository.findAllByNodeExecutionIdInOrderByCreatedAtAsc(nodeExecIds);
  }

  private String getNodeKey(NodeExecution ne) {
    return jdbcTemplate.queryForObject(
        "SELECT node_key FROM workflow_nodes WHERE id = ?", String.class, ne.getNodeDefinitionId());
  }

  private <T> T asActor(UUID actorId, java.util.function.Supplier<T> supplier) {
    SecurityContext prevContext = SecurityContextHolder.getContext();
    try {
      var principal = new AuthenticatedActorPrincipal(actorId, actorId + "@demo.test");
      var auth =
          UsernamePasswordAuthenticationToken.authenticated(
              principal,
              "N/A",
              List.of(
                  new SimpleGrantedAuthority("ROLE_USER"),
                  new SimpleGrantedAuthority("ROLE_ADMIN"),
                  new SimpleGrantedAuthority("ROLE_WORKFLOW_OWNER")));
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(auth);
      SecurityContextHolder.setContext(context);
      return supplier.get();
    } finally {
      SecurityContextHolder.setContext(prevContext);
    }
  }

  private void asActor(UUID actorId, Runnable runnable) {
    asActor(
        actorId,
        () -> {
          runnable.run();
          return null;
        });
  }
}
