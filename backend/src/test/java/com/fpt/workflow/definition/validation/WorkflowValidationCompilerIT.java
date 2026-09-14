package com.fpt.workflow.definition.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowValidationIssueRepository;
import com.fpt.workflow.definition.repository.WorkflowValidationRunRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.domain.ValidationSeverity;
import com.fpt.workflow.resolver.expression.ExpressionOperator;
import com.fpt.workflow.resolver.expression.LiteralExpression;
import com.fpt.workflow.resolver.expression.OperatorExpression;
import com.fpt.workflow.resolver.expression.ReferenceExpression;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class WorkflowValidationCompilerIT {

  private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("validation_compiler_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowValidationCompiler compiler;
  @Autowired private WorkflowValidationService validationService;
  @Autowired private WorkflowDefinitionRepository definitionRepository;
  @Autowired private WorkflowVersionRepository versionRepository;
  @Autowired private WorkflowValidationRunRepository runRepository;
  @Autowired private WorkflowValidationIssueRepository issueRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void validGraphExecutesAllCompilerStages() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());
    EdgeDefinition edge = edge(version, start, "STARTED", end, null, false, 0);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version, List.of(end, start), List.of(edge), List.of(), List.of()));

    assertThat(result.valid()).isTrue();
    assertThat(result.executedStages()).containsExactly(ValidationStage.values());
    assertThat(result.definitionChecksum()).hasSize(64);
  }

  @Test
  void reportsStructuralRoutingConfigAndTypedReferenceFailuresWithStableCodes() {
    WorkflowVersion version = version();
    NodeDefinition startOne =
        node(version, "startOne", "START", objectMapper.createObjectNode().put("rogue", true));
    NodeDefinition startTwo = node(version, "startTwo", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());
    NodeDefinition orphan = node(version, "orphan", "REVIEW", objectMapper.createObjectNode());
    var condition =
        OperatorExpression.of(
            ExpressionOperator.EQ,
            new ReferenceExpression("ticket.unknown"),
            new LiteralExpression(
                JsonNodeFactory.instance.numberNode(1),
                TypeDescriptor.required(CanonicalValueType.INTEGER)));
    EdgeDefinition invalidPort =
        edge(version, startOne, "BOGUS", end, objectMapper.valueToTree(condition), false, 0);
    EdgeDefinition duplicatePriority =
        edge(
            version,
            startOne,
            "BOGUS",
            UUID.randomUUID(),
            objectMapper.valueToTree(condition),
            false,
            0);
    EdgeDefinition endOutgoing = edge(version, end, "COMPLETED", startOne, null, false, 2);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version,
                List.of(startOne, startTwo, end, orphan),
                List.of(invalidPort, duplicatePriority, endOutgoing),
                List.of(),
                List.of()));

    assertThat(result.valid()).isFalse();
    assertThat(result.issues())
        .extracting(CompilerIssue::code)
        .contains(
            "MULTIPLE_START",
            "END_HAS_OUTGOING",
            "MISSING_TARGET",
            "INVALID_OUTPUT_PORT",
            "UNREACHABLE_NODE",
            "NONTERMINAL_DEAD_END",
            "UNHANDLED_PORT",
            "ROUTING_NON_DETERMINISTIC",
            "ROUTING_DEFAULT_REQUIRED",
            "INVALID_TYPED_REFERENCE");
    assertThat(result.issues()).anyMatch(issue -> issue.code().contains("UNKNOWN_PROPERTY"));
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void persistsFreshRunAndIssues() {
    WorkflowDefinition definition =
        definitionRepository.save(
            WorkflowDefinition.create(
                UUID.randomUUID(),
                "validation_" + UUID.randomUUID().toString().replace("-", ""),
                "Validation",
                null,
                ACTOR,
                ACTOR,
                Instant.now()));
    WorkflowVersion version =
        versionRepository.save(
            WorkflowVersion.createDraft(
                UUID.randomUUID(), definition.getId(), 1, null, null, ACTOR, Instant.now()));

    WorkflowValidationService.PersistedValidation persisted =
        validationService.validate(version.getId());

    assertThat(runRepository.findById(persisted.run().getId())).isPresent();
    assertThat(
            issueRepository.findAllByValidationRunIdOrderBySeverityAscRuleCodeAsc(
                persisted.run().getId()))
        .extracting(com.fpt.workflow.definition.domain.WorkflowValidationIssue::getRuleCode)
        .contains("NO_START", "NO_END");
    assertThat(persisted.run().getRevision()).isEqualTo(version.getRevision());
  }

  @Test
  void multiInstanceThresholdPolicy_withoutRemainingItemPolicy_failsValidation() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());

    var miConfig = objectMapper.createObjectNode();
    var mi = miConfig.putObject("multiInstance");
    mi.put("collection", "${ticket.data.items}");
    mi.put("completionPolicy", "ANY");
    // remainingItemPolicy intentionally omitted!
    var participant = miConfig.putObject("participant");
    participant.put("type", "FIXED_USER");
    participant.put("userId", UUID.randomUUID().toString());
    var actions = miConfig.putArray("allowedActions");
    actions.add("SUBMIT");
    actions.add("RETURN");

    NodeDefinition miNode = node(version, "mi_review", "REVIEW", miConfig);

    EdgeDefinition edge1 = edge(version, start, "STARTED", miNode, null, false, 0);
    EdgeDefinition edge2 = edge(version, miNode, "SUBMITTED", end, null, false, 0);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version, List.of(start, miNode, end), List.of(edge1, edge2), List.of(), List.of()));

    assertThat(result.valid()).isFalse();
    assertThat(result.issues())
        .extracting(CompilerIssue::code)
        .contains("MULTI_INSTANCE_REMAINING_ITEM_POLICY_REQUIRED");
  }

  @Test
  void multiInstance_withInvalidRemainingItemPolicy_failsValidation() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());

    var miConfig = objectMapper.createObjectNode();
    var mi = miConfig.putObject("multiInstance");
    mi.put("collection", "${ticket.data.items}");
    mi.put("completionPolicy", "ANY");
    mi.put("remainingItemPolicy", "INVALID_POLICY");
    var participant = miConfig.putObject("participant");
    participant.put("type", "FIXED_USER");
    participant.put("userId", UUID.randomUUID().toString());
    var actions = miConfig.putArray("allowedActions");
    actions.add("SUBMIT");
    actions.add("RETURN");

    NodeDefinition miNode = node(version, "mi_review", "REVIEW", miConfig);

    EdgeDefinition edge1 = edge(version, start, "STARTED", miNode, null, false, 0);
    EdgeDefinition edge2 = edge(version, miNode, "SUBMITTED", end, null, false, 0);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version, List.of(start, miNode, end), List.of(edge1, edge2), List.of(), List.of()));

    assertThat(result.valid()).isFalse();
    assertThat(result.issues())
        .extracting(CompilerIssue::code)
        .contains("MULTI_INSTANCE_REMAINING_ITEM_POLICY_INVALID");
  }

  @Test
  void joinGateway_anyPolicyWithoutRemainingBranchPolicy_failsValidation() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());

    var joinConfig = objectMapper.createObjectNode();
    joinConfig.put("policy", "FIRST");
    // remainingBranchPolicy intentionally omitted!
    NodeDefinition joinNode = node(version, "join", "JOIN", joinConfig);

    EdgeDefinition edge1 = edge(version, start, "STARTED", joinNode, null, false, 0);
    EdgeDefinition edge2 = edge(version, joinNode, "DEFAULT", end, null, false, 0);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version, List.of(start, joinNode, end), List.of(edge1, edge2), List.of(), List.of()));

    assertThat(result.valid()).isFalse();
    assertThat(result.issues())
        .extracting(CompilerIssue::code)
        .contains("JOIN_REMAINING_BRANCH_POLICY_REQUIRED");
  }

  @Test
  void multiInstance_and_join_withValidPolicies_passValidation() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());

    var miConfig = objectMapper.createObjectNode();
    var mi = miConfig.putObject("multiInstance");
    mi.put("collection", "${ticket.data.items}");
    mi.put("itemVariable", "employee");
    mi.put("completionPolicy", "ANY");
    mi.put("remainingItemPolicy", "CANCEL_REMAINING");
    var participant = miConfig.putObject("participant");
    participant.put("type", "FIXED_USER");
    participant.put("userId", UUID.randomUUID().toString());
    var actions = miConfig.putArray("allowedActions");
    actions.add("SUBMIT");
    actions.add("RETURN");

    NodeDefinition miNode = node(version, "mi_review", "REVIEW", miConfig);

    var joinConfig = objectMapper.createObjectNode();
    joinConfig.put("policy", "FIRST");
    joinConfig.put("remainingBranchPolicy", "CANCEL_REMAINING");
    NodeDefinition joinNode = node(version, "join", "JOIN", joinConfig);

    EdgeDefinition edge1 = edge(version, start, "STARTED", miNode, null, false, 0);
    EdgeDefinition edge2 = edge(version, miNode, "SUBMITTED", joinNode, null, false, 0);
    EdgeDefinition edge3 = edge(version, miNode, "RETURNED", end, null, false, 0);
    EdgeDefinition edge4 = edge(version, joinNode, "DEFAULT", end, null, false, 0);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version,
                List.of(start, miNode, joinNode, end),
                List.of(edge1, edge2, edge3, edge4),
                List.of(),
                List.of()));

    assertThat(result.valid()).isTrue();
    assertThat(result.issues()).isEmpty();
  }

  @Test
  void participantValidation_validatesParticipantParametersAndAck() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());

    // 1. Invalid UUID in FIXED_USER
    var invalidUserCfg = objectMapper.createObjectNode();
    var p1 = invalidUserCfg.putObject("participant");
    p1.put("type", "FIXED_USER");
    p1.put("userId", "");
    invalidUserCfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    NodeDefinition node1 = node(version, "review1", "REVIEW", invalidUserCfg);

    // 2. Missing groupId in GROUP_MEMBERS
    var missingGroupCfg = objectMapper.createObjectNode();
    var p2 = missingGroupCfg.putObject("participant");
    p2.put("type", "GROUP_MEMBERS");
    p2.put("ackRequired", true);
    missingGroupCfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    NodeDefinition node2 = node(version, "review2", "REVIEW", missingGroupCfg);

    EdgeDefinition e1 = edge(version, start, "STARTED", node1, null, false, 0);
    EdgeDefinition e2 = edge(version, node1, "SUBMITTED", node2, null, false, 0);
    EdgeDefinition e3 = edge(version, node1, "RETURNED", end, null, false, 0);
    EdgeDefinition e4 = edge(version, node2, "SUBMITTED", end, null, false, 0);
    EdgeDefinition e5 = edge(version, node2, "RETURNED", end, null, false, 0);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version,
                List.of(start, node1, node2, end),
                List.of(e1, e2, e3, e4, e5),
                List.of(),
                List.of()));

    assertThat(result.valid()).isFalse();
    assertThat(result.issues())
        .extracting(CompilerIssue::code)
        .contains("FIXED_USER_ID_REQUIRED", "GROUP_MEMBERS_KEY_REQUIRED", "PARTICIPANT_RESOLUTION_ACK_REQUIRED");
  }

  @Test
  void joinGateway_cancelRemainingWithAck_emitsAckRequiredWarning() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());

    var joinConfig = objectMapper.createObjectNode();
    joinConfig.put("policy", "FIRST");
    joinConfig.put("remainingBranchPolicy", "CANCEL_REMAINING");
    joinConfig.put("ackRequired", true);
    NodeDefinition joinNode = node(version, "join", "JOIN", joinConfig);

    EdgeDefinition edge1 = edge(version, start, "STARTED", joinNode, null, false, 0);
    EdgeDefinition edge2 = edge(version, joinNode, "DEFAULT", end, null, false, 0);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version, List.of(start, joinNode, end), List.of(edge1, edge2), List.of(), List.of()));

    assertThat(result.issues())
        .extracting(CompilerIssue::code)
        .contains("JOIN_CANCEL_REMAINING_ACK_REQUIRED");
    assertThat(result.issues())
        .filteredOn(i -> "JOIN_CANCEL_REMAINING_ACK_REQUIRED".equals(i.code()))
        .extracting(CompilerIssue::severity)
        .contains(ValidationSeverity.ACK_REQUIRED_WARNING);
  }

  @Test
  void crossNodeValidation_catchesSelfReferenceAndMissingTarget() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());

    var step1Cfg = objectMapper.createObjectNode();
    step1Cfg.put("expr", "${nodes.step1.output.value}"); // self reference!
    NodeDefinition step1 = node(version, "step1", "TASK", step1Cfg);

    var step2Cfg = objectMapper.createObjectNode();
    step2Cfg.put("expr", "${nodes.nonexistent.output.value}"); // missing target!
    NodeDefinition step2 = node(version, "step2", "TASK", step2Cfg);

    EdgeDefinition e1 = edge(version, start, "STARTED", step1, null, false, 0);
    EdgeDefinition e2 = edge(version, step1, "COMPLETED", step2, null, false, 0);
    EdgeDefinition e3 = edge(version, step2, "COMPLETED", end, null, false, 0);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version,
                List.of(start, step1, step2, end),
                List.of(e1, e2, e3),
                List.of(),
                List.of()));

    assertThat(result.valid()).isFalse();
    assertThat(result.issues())
        .extracting(CompilerIssue::code)
        .contains("CROSS_NODE_SELF_REFERENCE", "CROSS_NODE_TARGET_NOT_FOUND");
  }

  @Test
  void subworkflowAndSlaValidation_detectsSlaErrorsAndChildSuspension() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());

    var subCfg = objectMapper.createObjectNode();
    subCfg.put("workflowKey", "child_sub");
    subCfg.put("childSuspended", true);
    var sla = subCfg.putObject("sla");
    sla.put("durationMinutes", 0); // invalid <= 0!
    sla.put("timeoutAction", "INVALID_ACTION");
    NodeDefinition subNode = node(version, "sub", "SUB_WORKFLOW", subCfg);

    EdgeDefinition e1 = edge(version, start, "STARTED", subNode, null, false, 0);
    EdgeDefinition e2 = edge(version, subNode, "COMPLETED", end, null, false, 0);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version,
                List.of(start, subNode, end),
                List.of(e1, e2),
                List.of(),
                List.of()));

    assertThat(result.issues())
        .extracting(CompilerIssue::code)
        .contains("INVALID_SLA_DURATION", "INVALID_SLA_TIMEOUT_ACTION", "CHILD_WORKFLOW_SUSPENDED");
    assertThat(result.issues())
        .filteredOn(i -> "CHILD_WORKFLOW_SUSPENDED".equals(i.code()))
        .extracting(CompilerIssue::severity)
        .contains(ValidationSeverity.ACK_REQUIRED_WARNING);
  }

  @Test
  void systemActionTimeout_invalidAndExcessiveValues_failValidation() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", objectMapper.createObjectNode());
    NodeDefinition end = node(version, "end", "END", objectMapper.createObjectNode());

    var cfgNegative = objectMapper.createObjectNode();
    cfgNegative.put("connectorKey", "conn");
    cfgNegative.put("actionKey", "act");
    cfgNegative.put("actionVersion", 1);
    cfgNegative.put("timeoutMs", 0);
    NodeDefinition negative = node(version, "sys_neg", "SYSTEM_ACTION", cfgNegative);

    var cfgExcess = objectMapper.createObjectNode();
    cfgExcess.put("connectorKey", "conn");
    cfgExcess.put("actionKey", "act");
    cfgExcess.put("actionVersion", 1);
    cfgExcess.put("timeoutMs", 999_999_999L);
    NodeDefinition excess = node(version, "sys_excess", "SYSTEM_ACTION", cfgExcess);

    var cfgOk = objectMapper.createObjectNode();
    cfgOk.put("connectorKey", "conn");
    cfgOk.put("actionKey", "act");
    cfgOk.put("actionVersion", 1);
    cfgOk.put("timeoutMs", 15_000);
    NodeDefinition bounded = node(version, "sys_ok", "SYSTEM_ACTION", cfgOk);

    ValidationCompilation result =
        compiler.compile(
            new ValidationDefinition(
                version,
                List.of(start, negative, excess, bounded, end),
                List.of(
                    edge(version, start, "STARTED", negative, null, false, 0),
                    edge(version, negative, "SUCCESS", excess, null, false, 0),
                    edge(version, excess, "SUCCESS", bounded, null, false, 0),
                    edge(version, bounded, "SUCCESS", end, null, false, 0)),
                List.of(),
                List.of()));

    assertThat(result.issues())
        .extracting(CompilerIssue::code)
        .contains("SYSTEM_ACTION_TIMEOUT_INVALID", "SYSTEM_ACTION_TIMEOUT_EXCEEDED");
    assertThat(result.issues())
        .filteredOn(i -> i.code().startsWith("SYSTEM_ACTION_TIMEOUT"))
        .noneMatch(i -> i.resourceId().equals(bounded.getId()));
  }

  private WorkflowVersion version() {
    return WorkflowVersion.createDraft(
        UUID.randomUUID(), UUID.randomUUID(), 1, null, null, ACTOR, Instant.EPOCH);
  }

  private NodeDefinition node(
      WorkflowVersion version,
      String key,
      String type,
      com.fasterxml.jackson.databind.JsonNode config) {
    return NodeDefinition.create(
        UUID.randomUUID(),
        version.getId(),
        key,
        type,
        key,
        null,
        1,
        config,
        null,
        null,
        objectMapper.createObjectNode());
  }

  private EdgeDefinition edge(
      WorkflowVersion version,
      NodeDefinition source,
      String port,
      NodeDefinition target,
      com.fasterxml.jackson.databind.JsonNode condition,
      boolean defaultEdge,
      int priority) {
    return edge(version, source, port, target.getId(), condition, defaultEdge, priority);
  }

  private EdgeDefinition edge(
      WorkflowVersion version,
      NodeDefinition source,
      String port,
      UUID target,
      com.fasterxml.jackson.databind.JsonNode condition,
      boolean defaultEdge,
      int priority) {
    return EdgeDefinition.create(
        UUID.randomUUID(),
        version.getId(),
        source.getId(),
        port,
        target,
        condition,
        priority,
        defaultEdge,
        condition == null ? TransitionType.NORMAL : TransitionType.CONDITIONAL,
        null,
        objectMapper.createObjectNode());
  }
}
