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
