package com.fpt.workflow.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.repository.ActivationTokenRepository;
import com.fpt.workflow.runtime.routing.repository.RoutingDecisionRepository;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.sla.domain.SlaExecution;
import com.fpt.workflow.sla.repository.SlaExecutionRepository;
import com.fpt.workflow.sla.service.DefaultSlaActionExecutor;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskDecisionRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.task.service.TaskCommandService;
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
class SlaTimeoutRuntimeIT {

  private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID RECOVERY_ACTOR =
      UUID.fromString("10000000-0000-4000-8000-000000000002");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_sla_runtime_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private DefaultSlaActionExecutor slaActions;
  @Autowired private SlaExecutionRepository slas;
  @Autowired private TaskExecutionRepository tasks;
  @Autowired private TaskDecisionRepository decisions;
  @Autowired private NodeExecutionRepository executions;
  @Autowired private EventRepository events;
  @Autowired private RoutingDecisionRepository routingDecisions;
  @Autowired private ActivationTokenRepository activationTokens;
  @Autowired private ParticipantSnapshotRepository participantSnapshots;
  @Autowired private AuditEventRepository audits;
  @Autowired private TaskCommandService taskCommands;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void gotoNodeRoutesThroughPersistedDecisionAndReplayDoesNotActivateTwice() {
    Fixture fixture = fixture("GOTO_NODE", "TIMEOUT", true, config -> config.put("timeoutTargetNodeKey", "end"));

    var first = execute(fixture.slaId());
    var replay = execute(fixture.slaId());

    assertThat(first.applied()).isTrue();
    assertThat(first.status()).startsWith("GOTO_NODE:");
    assertThat(replay.applied()).isFalse();
    assertThat(routingDecisions.findBySourceNodeExecutionId(fixture.executionId())).isPresent();
    var decision = routingDecisions.findBySourceNodeExecutionId(fixture.executionId()).orElseThrow();
    assertThat(activationTokens.findAllByRoutingDecisionId(decision.getId())).hasSize(1);
    assertThat(executions.findAllByEventIdAndNodeDefinitionIdOrderByCreatedAtAsc(
            fixture.eventId(), fixture.targetNodeId()))
        .hasSize(1);
    assertThat(tasks.findById(fixture.taskId()).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.EXPIRED);
  }

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000002")
  void createManualTaskIsActionableSnapshotBackedAndRetrySafe() {
    Fixture fixture =
        fixture(
            "CREATE_MANUAL_TASK",
            "APPROVED",
            true,
            config -> {
              ObjectNode manual = config.putObject("manualTask");
              manual.put("title", "Recover timed-out approval");
              manual.putObject("participant").put("type", "FIXED_USER").put("userId", RECOVERY_ACTOR.toString());
            });

    var first = execute(fixture.slaId());
    var replay = execute(fixture.slaId());
    List<TaskExecution> nodeTasks =
        tasks.findAllByNodeExecutionIdOrderByCreatedAtAsc(fixture.executionId());

    assertThat(first.applied()).isTrue();
    assertThat(replay.applied()).isFalse();
    assertThat(nodeTasks).hasSize(2);
    TaskExecution manual = nodeTasks.getLast();
    assertThat(manual.getStatus()).isEqualTo(TaskStatus.READY);
    assertThat(manual.getAssigneeId()).isEqualTo(RECOVERY_ACTOR);
    assertThat(manual.getTitleSnapshot()).isEqualTo("Recover timed-out approval");
    assertThat(participantSnapshots.findAllByNodeExecutionIdOrderByResolvedAtAsc(fixture.executionId()))
        .extracting(ParticipantSnapshot::getResolvedUserId)
        .contains(RECOVERY_ACTOR);

    taskCommands.decideTask(
        manual.getId(),
        BusinessOutcome.APPROVED,
        JsonNodeFactory.instance.objectNode(),
        "Recovered after SLA timeout",
        new CorrelationId(UUID.randomUUID()),
        new CommandId(UUID.randomUUID()));
    assertThat(executions.findById(fixture.executionId()).orElseThrow().getStatus())
        .isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(executions.findAllByEventIdAndNodeDefinitionIdOrderByCreatedAtAsc(
            fixture.eventId(), fixture.targetNodeId()))
        .hasSize(1);
  }

  @Test
  void failNodeUsesRuntimeStateMachineAndFailsEvent() {
    Fixture fixture = fixture("FAIL_NODE", "IGNORED", false, config -> {});

    execute(fixture.slaId());

    NodeExecution execution = executions.findById(fixture.executionId()).orElseThrow();
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.FAILED);
    assertThat(execution.getErrorJson().path("code").asText()).isEqualTo("SLA_TIMEOUT");
    assertThat(events.findById(fixture.eventId()).orElseThrow().getStatus())
        .isEqualTo(EventStatus.FAILED);
    assertThat(audits.findAllByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(
            "SLA_EXECUTION", fixture.slaId()))
        .extracting(audit -> audit.getEventType())
        .containsExactly("SLA_NODE_FAILED");
  }

  @Test
  void autoRejectUsesCanonicalTaskDecisionNodeRoutingAndAudit() {
    Fixture fixture = fixture("AUTO_REJECT", "REJECTED", true, config -> {});

    execute(fixture.slaId());

    assertThat(tasks.findById(fixture.taskId()).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.COMPLETED);
    assertThat(decisions.findByTaskId(fixture.taskId()))
        .get()
        .satisfies(decision -> assertThat(decision.getOutcome()).isEqualTo("REJECTED"));
    assertThat(routingDecisions.findBySourceNodeExecutionId(fixture.executionId())).isPresent();
    assertThat(executions.findAllByEventIdAndNodeDefinitionIdOrderByCreatedAtAsc(
            fixture.eventId(), fixture.targetNodeId()))
        .hasSize(1);
  }

  @Test
  void escalationUsesCommonRegistryAndPersistsImmutableRecipientSnapshot() {
    Fixture fixture =
        fixture(
            "ESCALATE",
            "IGNORED",
            false,
            config ->
                config
                    .putObject("escalationResolver")
                    .put("type", "FIXED_USER")
                    .put("userId", RECOVERY_ACTOR.toString()));

    execute(fixture.slaId());

    assertThat(tasks.findById(fixture.taskId()).orElseThrow().getAssigneeId())
        .isEqualTo(RECOVERY_ACTOR);
    List<ParticipantSnapshot> snapshots =
        participantSnapshots.findAllByNodeExecutionIdOrderByResolvedAtAsc(fixture.executionId());
    assertThat(snapshots).singleElement();
    ParticipantSnapshot historical = snapshots.getFirst();
    assertThat(historical.getResolverType()).isEqualTo("FIXED_USER");
    assertThat(historical.getResolvedUserId()).isEqualTo(RECOVERY_ACTOR);
    assertThat(historical.getResolverConfigJson().path("userId").asText())
        .isEqualTo(RECOVERY_ACTOR.toString());
  }

  private DefaultSlaActionExecutor.ExecutionResult execute(UUID slaId) {
    return slaActions.execute(
        slaId, new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));
  }

  private Fixture fixture(
      String timeoutAction,
      String edgePort,
      boolean createEdge,
      java.util.function.Consumer<ObjectNode> configurer) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID definitionId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID sourceNodeId = UUID.randomUUID();
    UUID targetNodeId = UUID.randomUUID();
    UUID requestTypeId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID slaId = UUID.randomUUID();
    Instant now = Instant.now();

    jdbc.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) VALUES (?, ?, 'SLA Test', 'ACTIVE', ?, ?, now(), now())",
        definitionId,
        "sla-" + suffix,
        ACTOR,
        ACTOR);
    jdbc.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        versionId,
        definitionId,
        ACTOR);
    jdbc.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) VALUES (?, ?, 'approval', 'APPROVAL', 'Approval', 1, '{}'::jsonb, '{}'::jsonb)",
        sourceNodeId,
        versionId);
    jdbc.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) VALUES (?, ?, 'end', 'END', 'End', 1, '{\"outcome\":\"DONE\"}'::jsonb, '{}'::jsonb)",
        targetNodeId,
        versionId);
    if (createEdge) {
      jdbc.update(
          "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) VALUES (?, ?, ?, ?, ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
          UUID.randomUUID(),
          versionId,
          sourceNodeId,
          edgePort,
          targetNodeId);
    }
    jdbc.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'sla-test', execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() WHERE id = ?",
        ACTOR,
        versionId);
    jdbc.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, creation_policy_json, created_at, updated_at) VALUES (?, ?, 'SLA Request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        requestTypeId,
        "sla-request-" + suffix,
        definitionId);
    jdbc.update(
        "INSERT INTO tickets (id, request_type_id, creator_id, status, data_json, created_at, updated_at) VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, now(), now())",
        ticketId,
        requestTypeId,
        ACTOR);
    jdbc.update(
        "INSERT INTO ticket_revisions (id, ticket_id, revision_no, data_snapshot_json, source_schema_version, schema_checksum, submitted_by, submitted_at) VALUES (?, ?, 1, '{}'::jsonb, 'v1', 'checksum', ?, now())",
        revisionId,
        ticketId,
        ACTOR);
    jdbc.update(
        "UPDATE tickets SET status = 'SUBMITTED', data_revision = 1, current_revision_id = ?, submitted_at = now(), updated_at = now() WHERE id = ?",
        revisionId,
        ticketId);

    Event event =
        Event.createRoot(
            eventId,
            ticketId,
            versionId,
            revisionId,
            null,
            null,
            "SLA_TEST",
            "sla-" + suffix,
            objectMapper.createObjectNode(),
            ACTOR,
            now.minusSeconds(120));
    event.markRunning();
    event.waitFor(RuntimeWaitReason.HUMAN_TASK);
    events.saveAndFlush(event);

    NodeExecution execution =
        NodeExecution.create(
            executionId,
            eventId,
            sourceNodeId,
            "sla:" + suffix,
            UUID.randomUUID(),
            0,
            "root",
            null,
            null,
            null,
            objectMapper.createObjectNode(),
            revisionId,
            now.minusSeconds(120));
    execution.markReady();
    execution.start(now.minusSeconds(110));
    execution.waitFor(RuntimeWaitReason.HUMAN_TASK);
    executions.saveAndFlush(execution);

    TaskExecution task =
        TaskExecution.create(
            taskId,
            executionId,
            null,
            ACTOR,
            "Timed approval",
            null,
            null,
            objectMapper.createObjectNode(),
            50,
            now.minusSeconds(1),
            now.minusSeconds(100));
    tasks.saveAndFlush(task);

    ObjectNode config = objectMapper.createObjectNode().put("timeoutAction", timeoutAction);
    configurer.accept(config);
    slas.saveAndFlush(
        SlaExecution.start(
            slaId,
            eventId,
            executionId,
            taskId,
            null,
            config,
            now.minusSeconds(100),
            now.minusSeconds(1),
            now.minusSeconds(1)));
    return new Fixture(eventId, executionId, targetNodeId, taskId, slaId);
  }

  private record Fixture(
      UUID eventId, UUID executionId, UUID targetNodeId, UUID taskId, UUID slaId) {}
}
