package com.fpt.workflow.resolver.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.organization.service.ManagerNotFoundException;
import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParticipantResolutionEngineTest {

  private OrganizationHierarchyService hierarchyService;
  private EmployeeRepository employeeRepository;
  private ParticipantResolverRegistry registry;
  private ParticipantResolutionEngine engine;

  private final Instant now = Instant.parse("2026-09-10T12:00:00Z");
  private final UUID creatorId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    hierarchyService = mock(OrganizationHierarchyService.class);
    employeeRepository = mock(EmployeeRepository.class);

    List<ParticipantResolver> resolvers =
        List.of(
            new FixedUserParticipantResolver(),
            new CreatorParticipantResolver(),
            new ManagerOfParticipantResolver(hierarchyService),
            new HeadOfUnitParticipantResolver(hierarchyService),
            new RequestFieldParticipantResolver(),
            new RoleMembersParticipantResolver(),
            new GroupMembersParticipantResolver(),
            new PreviousParticipantResolver(),
            new NodeOutputParticipantResolver(),
            new ExpressionParticipantResolver());
    registry = new ParticipantResolverRegistry(resolvers);
    engine = new ParticipantResolutionEngine(registry, employeeRepository);
  }

  @Test
  @DisplayName("Primary resolver (FIXED_USER) resolves successfully")
  void primaryResolvesSuccessfully() {
    UUID targetUser = UUID.randomUUID();
    ObjectNode config = JsonNodeFactory.instance.objectNode();
    config.putObject("participant").put("type", "FIXED_USER").put("userId", targetUser.toString());

    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, creatorId, null, config.path("participant"), now);

    ParticipantResolutionEngine.ResolutionOutcome outcome = engine.resolve(config, context, null);

    assertThat(outcome.result().isResolved()).isTrue();
    assertThat(outcome.result().users()).containsExactly(targetUser);
    assertThat(outcome.appliedStage()).isEqualTo("PRIMARY");
    assertThat(outcome.evaluationTrace()).hasSize(1);
  }

  @Test
  @DisplayName("Evaluates fallback chain when primary (MANAGER_OF) is vacant")
  void evaluatesFallbackWhenPrimaryVacant() {
    UUID deptHeadId = UUID.randomUUID();
    when(hierarchyService.resolveManagerAtDepth(eq(creatorId), eq(1), any()))
        .thenThrow(new ManagerNotFoundException("No manager found"));
    when(hierarchyService.resolveDepartmentHeadUserId(any(), any())).thenReturn(Optional.of(deptHeadId));
    when(hierarchyService.resolveHeadOfUnitForUser(eq(creatorId), eq("DEPARTMENT"), any()))
        .thenReturn(Optional.of(deptHeadId));

    ObjectNode config = JsonNodeFactory.instance.objectNode();
    ObjectNode pNode = config.putObject("participant");
    pNode.put("type", "MANAGER_OF");
    ArrayNode fallbacks = pNode.putArray("fallbackChain");
    fallbacks.addObject().put("type", "HEAD_OF_UNIT").put("unitType", "DEPARTMENT");

    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, creatorId, null, pNode, now);

    ParticipantResolutionEngine.ResolutionOutcome outcome = engine.resolve(config, context, null);

    assertThat(outcome.result().isResolved()).isTrue();
    assertThat(outcome.result().users()).containsExactly(deptHeadId);
    assertThat(outcome.appliedStage()).isEqualTo("FALLBACK_1");
    assertThat(outcome.evaluationTrace()).hasSize(2);
    assertThat(outcome.evaluationTrace().get(0).status()).isEqualTo(ParticipantResolutionStatus.VACANT);
    assertThat(outcome.evaluationTrace().get(1).status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
  }

  @Test
  @DisplayName("Evaluates fallback chain to WORKFLOW_OWNER when manager and head of unit are vacant")
  void evaluatesFallbackToWorkflowOwner() {
    UUID workflowOwnerId = UUID.randomUUID();
    when(hierarchyService.resolveManagerAtDepth(eq(creatorId), eq(1), any()))
        .thenThrow(new ManagerNotFoundException("No manager found"));
    when(hierarchyService.resolveHeadOfUnitForUser(any(), any(), any())).thenReturn(Optional.empty());

    ObjectNode config = JsonNodeFactory.instance.objectNode();
    ObjectNode participant = config.putObject("participant");
    participant.put("type", "MANAGER_OF");
    ArrayNode fallbacks = participant.putArray("fallbackChain");
    fallbacks.addObject().put("type", "HEAD_OF_UNIT").put("unitType", "DEPARTMENT");
    fallbacks.addObject().put("type", "WORKFLOW_OWNER");

    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, creatorId, null, config.path("participant"), now);

    ParticipantResolutionEngine.ResolutionOutcome outcome =
        engine.resolve(config, context, workflowOwnerId);

    assertThat(outcome.result().isResolved()).isTrue();
    assertThat(outcome.result().users()).containsExactly(workflowOwnerId);
    assertThat(outcome.appliedStage()).isEqualTo("FALLBACK_2");
    assertThat(outcome.evaluationTrace()).hasSize(3);
  }

  @Test
  @DisplayName("MANAGER_OF does not invent a business fallback when none is configured")
  void managerWithoutConfiguredFallbackDoesNotGuess() {
    UUID workflowOwnerId = UUID.randomUUID();
    when(hierarchyService.resolveManagerAtDepth(eq(creatorId), eq(1), any()))
        .thenThrow(new ManagerNotFoundException("No manager found"));

    ObjectNode config = JsonNodeFactory.instance.objectNode();
    config.putObject("participant").put("type", "MANAGER_OF");
    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, creatorId, null, config.path("participant"), now);

    ParticipantResolutionEngine.ResolutionOutcome outcome =
        engine.resolve(config, context, workflowOwnerId);

    assertThat(outcome.result().status()).isEqualTo(ParticipantResolutionStatus.VACANT);
    assertThat(outcome.appliedStage()).isEqualTo("EXHAUSTED");
    assertThat(outcome.evaluationTrace()).hasSize(1);
  }

  @Test
  @DisplayName("Exhausted fallback chain reports OnMissingPolicy.FAIL_NODE")
  void exhaustedFallbackChainWithFailNode() {
    when(hierarchyService.resolveManagerAtDepth(eq(creatorId), eq(1), any()))
        .thenThrow(new ManagerNotFoundException("No manager found"));
    when(hierarchyService.resolveHeadOfUnitForUser(any(), any(), any())).thenReturn(Optional.empty());

    ObjectNode config = JsonNodeFactory.instance.objectNode();
    ObjectNode pNode = config.putObject("participant");
    pNode.put("type", "MANAGER_OF");
    pNode.put("onMissing", "FAIL_NODE");

    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, creatorId, null, pNode, now);

    ParticipantResolutionEngine.ResolutionOutcome outcome = engine.resolve(config, context, null);

    assertThat(outcome.result().isResolved()).isFalse();
    assertThat(outcome.appliedStage()).isEqualTo("EXHAUSTED");
    assertThat(outcome.onMissingPolicy()).isEqualTo(ParticipantResolutionEngine.OnMissingPolicy.FAIL_NODE);
  }

  @Test
  @DisplayName("Exhausted fallback chain reports OnMissingPolicy.CREATE_MANUAL_TASK")
  void exhaustedFallbackChainWithCreateManualTask() {
    when(hierarchyService.resolveManagerAtDepth(eq(creatorId), eq(1), any()))
        .thenThrow(new ManagerNotFoundException("No manager found"));
    when(hierarchyService.resolveHeadOfUnitForUser(any(), any(), any())).thenReturn(Optional.empty());

    ObjectNode config = JsonNodeFactory.instance.objectNode();
    ObjectNode pNode = config.putObject("participant");
    pNode.put("type", "MANAGER_OF");
    pNode.put("onMissing", "CREATE_MANUAL_TASK");

    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, creatorId, null, pNode, now);

    ParticipantResolutionEngine.ResolutionOutcome outcome = engine.resolve(config, context, null);

    assertThat(outcome.result().isResolved()).isFalse();
    assertThat(outcome.appliedStage()).isEqualTo("EXHAUSTED");
    assertThat(outcome.onMissingPolicy()).isEqualTo(ParticipantResolutionEngine.OnMissingPolicy.CREATE_MANUAL_TASK);
  }

  @Test
  @DisplayName("Inactive assignee detection marks status INACTIVE_ASSIGNEE and triggers fallback")
  void inactiveAssigneeTriggersFallback() {
    UUID inactiveUser = UUID.randomUUID();
    UUID fallbackUser = UUID.randomUUID();

    Employee inactiveEmployee = mock(Employee.class);
    when(inactiveEmployee.isActive()).thenReturn(false);
    when(employeeRepository.findByUserId(inactiveUser)).thenReturn(Optional.of(inactiveEmployee));

    Employee activeEmployee = mock(Employee.class);
    when(activeEmployee.isActive()).thenReturn(true);
    when(employeeRepository.findByUserId(fallbackUser)).thenReturn(Optional.of(activeEmployee));

    ObjectNode config = JsonNodeFactory.instance.objectNode();
    ObjectNode pNode = config.putObject("participant");
    pNode.put("type", "FIXED_USER").put("userId", inactiveUser.toString());
    ArrayNode fallbacks = pNode.putArray("fallbackChain");
    fallbacks.addObject().put("type", "FIXED_USER").put("userId", fallbackUser.toString());

    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, creatorId, null, pNode, now);

    ParticipantResolutionEngine.ResolutionOutcome outcome = engine.resolve(config, context, null);

    assertThat(outcome.result().isResolved()).isTrue();
    assertThat(outcome.result().users()).containsExactly(fallbackUser);
    assertThat(outcome.appliedStage()).isEqualTo("FALLBACK_1");
    assertThat(outcome.evaluationTrace().get(0).status()).isEqualTo(ParticipantResolutionStatus.INACTIVE_ASSIGNEE);
  }

  @Test
  @DisplayName("Subject resolution resolves from REQUEST_FIELD in ticket data")
  void subjectResolutionFromRequestField() {
    UUID subjectUser = UUID.randomUUID();
    UUID managerId = UUID.randomUUID();

    ObjectNode ticketData = JsonNodeFactory.instance.objectNode();
    ticketData.put("targetEmployeeId", subjectUser.toString());

    ObjectNode config = JsonNodeFactory.instance.objectNode();
    ObjectNode pNode = config.putObject("participant");
    pNode.put("type", "MANAGER_OF");
    pNode.put("subject", "REQUEST_FIELD");
    pNode.put("subjectField", "targetEmployeeId");

    ParticipantResolverContext context =
        new ParticipantResolverContext(
            creatorId, creatorId, null, pNode, now, ticketData, null, null);

    when(hierarchyService.resolveManagerAtDepth(eq(subjectUser), eq(1), any())).thenReturn(managerId);

    ParticipantResolutionEngine.ResolutionOutcome outcome = engine.resolve(config, context, null);

    assertThat(outcome.result().isResolved()).isTrue();
    assertThat(outcome.result().users()).containsExactly(managerId);
  }

  @Test
  @DisplayName("Normalized Ticket subjects resolve different managers for different Tickets")
  void normalizedTicketSubjectsDriveManagerResolution() {
    UUID subjectA = UUID.randomUUID();
    UUID subjectB = UUID.randomUUID();
    UUID managerA = UUID.randomUUID();
    UUID managerB = UUID.randomUUID();
    when(hierarchyService.resolveManagerAtDepth(eq(subjectA), eq(1), any())).thenReturn(managerA);
    when(hierarchyService.resolveManagerAtDepth(eq(subjectB), eq(1), any())).thenReturn(managerB);

    ObjectNode config = JsonNodeFactory.instance.objectNode();
    ObjectNode participant = config.putObject("participant");
    participant.put("type", "MANAGER_OF");
    participant
        .putObject("subject")
        .put("type", "TICKET_SUBJECT")
        .put("role", "EVALUATION_TARGET");

    ParticipantResolutionEngine.ResolutionOutcome outcomeA =
        engine.resolve(config, subjectContext(participant, subjectA), null);
    ParticipantResolutionEngine.ResolutionOutcome outcomeB =
        engine.resolve(config, subjectContext(participant, subjectB), null);

    assertThat(outcomeA.result().users()).containsExactly(managerA);
    assertThat(outcomeB.result().users()).containsExactly(managerB);
  }

  @Test
  @DisplayName("Ambiguous normalized Ticket subject is not flattened to an arbitrary user")
  void ambiguousTicketSubjectRemainsAmbiguous() {
    UUID subjectA = UUID.randomUUID();
    UUID subjectB = UUID.randomUUID();
    ObjectNode config = JsonNodeFactory.instance.objectNode();
    ObjectNode participant = config.putObject("participant");
    participant.put("type", "MANAGER_OF");
    participant.putObject("subject").put("type", "TICKET_SUBJECT");
    ArrayNode subjects = JsonNodeFactory.instance.arrayNode();
    subjects.addObject().put("type", "EMPLOYEE").put("referenceId", subjectA.toString());
    subjects.addObject().put("type", "EMPLOYEE").put("referenceId", subjectB.toString());
    ParticipantResolverContext context =
        new ParticipantResolverContext(
            creatorId,
            creatorId,
            null,
            participant,
            now,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            null,
            subjects);

    ParticipantResolutionEngine.ResolutionOutcome outcome = engine.resolve(config, context, null);

    assertThat(outcome.result().status()).isEqualTo(ParticipantResolutionStatus.AMBIGUOUS);
    assertThat(outcome.result().users()).containsExactly(subjectA, subjectB);
  }

  @Test
  @DisplayName("Missing REQUEST_FIELD subject does not silently become the Ticket creator")
  void missingRequestFieldSubjectDoesNotFallbackToCreator() {
    ObjectNode config = JsonNodeFactory.instance.objectNode();
    ObjectNode participant = config.putObject("participant");
    participant.put("type", "MANAGER_OF");
    participant.put("subject", "REQUEST_FIELD");
    participant.put("subjectField", "missingEmployee");
    ParticipantResolverContext context =
        new ParticipantResolverContext(
            creatorId,
            creatorId,
            null,
            participant,
            now,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            null);

    ParticipantResolutionEngine.ResolutionOutcome outcome = engine.resolve(config, context, null);

    assertThat(outcome.result().status()).isEqualTo(ParticipantResolutionStatus.NOT_FOUND);
    org.mockito.Mockito.verifyNoInteractions(hierarchyService);
  }

  private ParticipantResolverContext subjectContext(ObjectNode participant, UUID subjectId) {
    ArrayNode subjects = JsonNodeFactory.instance.arrayNode();
    subjects
        .addObject()
        .put("type", "EMPLOYEE")
        .put("referenceId", subjectId.toString())
        .put("role", "EVALUATION_TARGET");
    return new ParticipantResolverContext(
        creatorId,
        creatorId,
        null,
        participant,
        now,
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        null,
        subjects);
  }
}
