package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.runtime.routing.domain.ActivationToken;
import com.fpt.workflow.runtime.routing.domain.ActivationTokenStatus;
import com.fpt.workflow.runtime.routing.domain.RoutingDecision;
import com.fpt.workflow.runtime.routing.repository.ActivationTokenRepository;
import com.fpt.workflow.runtime.routing.repository.RoutingDecisionRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P33: Verifies RoutingDecision + ActivationToken persistence, crash-recovery replay, and token
 * explainability.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class RoutingDecisionPersistenceIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_routing_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private RoutingService routingService;
  @Autowired private NodeActivationService activationService;
  @Autowired private RoutingDecisionRepository decisionRepository;
  @Autowired private ActivationTokenRepository tokenRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private NodeExecutionRepository executionRepository;
  @Autowired private UuidGenerator uuidGenerator;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-08T05:00:00Z");

  private UUID versionId;
  private UUID startNodeId;
  private UUID endNodeId;
  private UUID edgeId;
  private Event event;
  private NodeExecution completedExecution;

  @BeforeEach
  void setup() {
    jdbcTemplate.execute(
        "TRUNCATE activation_tokens, routing_decisions, node_executions, events,"
            + " workflow_edges, workflow_nodes, workflow_versions, workflow_definitions,"
            + " ticket_revisions, tickets, request_types RESTART IDENTITY CASCADE");

    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID definitionId = UUID.randomUUID();
    versionId = UUID.randomUUID();
    startNodeId = UUID.randomUUID();
    endNodeId = UUID.randomUUID();
    edgeId = UUID.randomUUID();
    UUID requestTypeId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();

    // 1. Workflow definition
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions "
            + "(id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Routing Test Workflow', 'ACTIVE', ?, ?, now(), now())",
        definitionId,
        "workflow-routing-" + suffix,
        ACTOR_ID,
        ACTOR_ID);

    // 2. Draft workflow version
    jdbcTemplate.update(
        "INSERT INTO workflow_versions "
            + "(id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        versionId,
        definitionId,
        ACTOR_ID);

    // 3. Workflow nodes (START and END)
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes "
            + "(id, workflow_version_id, node_key, node_type, name, config_schema_version, "
            + "config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start', 1, '{}'::jsonb, '{}'::jsonb)",
        startNodeId,
        versionId);

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes "
            + "(id, workflow_version_id, node_key, node_type, name, config_schema_version, "
            + "config_json, position_json) "
            + "VALUES (?, ?, 'end', 'END', 'End', 1, '{\"outcome\":\"APPROVED\"}'::jsonb, '{}'::jsonb)",
        endNodeId,
        versionId);

    // 4. Edge: start -> end (port 'STARTED')
    jdbcTemplate.update(
        "INSERT INTO workflow_edges "
            + "(id, workflow_version_id, source_node_id, source_port, target_node_id, "
            + "priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeId,
        versionId,
        startNodeId,
        endNodeId);

    // Publish workflow version after graph is complete
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'test-checksum', "
            + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() "
            + "WHERE id = ?",
        ACTOR_ID,
        versionId);

    // 5. Request type
    jdbcTemplate.update(
        "INSERT INTO request_types "
            + "(id, key, name, category, workflow_definition_id, active, "
            + "creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Routing Request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        requestTypeId,
        "req-routing-" + suffix,
        definitionId);

    // 6. Ticket + revision
    jdbcTemplate.update(
        "INSERT INTO tickets "
            + "(id, request_type_id, creator_id, status, data_json, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, now(), now())",
        ticketId,
        requestTypeId,
        ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO ticket_revisions "
            + "(id, ticket_id, revision_no, data_snapshot_json, source_schema_version, "
            + "schema_checksum, submitted_by, submitted_at) "
            + "VALUES (?, ?, 1, '{}'::jsonb, 'v1', 'checksum', ?, now())",
        revisionId,
        ticketId,
        ACTOR_ID);

    jdbcTemplate.update(
        "UPDATE tickets SET status = 'SUBMITTED', data_revision = 1, current_revision_id = ?, "
            + "submitted_at = now(), updated_at = now() WHERE id = ?",
        revisionId,
        ticketId);

    // 7. Create event
    UUID eventId = uuidGenerator.generate();
    event =
        Event.createRoot(
            eventId,
            ticketId,
            versionId,
            revisionId,
            null,
            null,
            "USER_SUBMIT",
            "corr-" + suffix,
            objectMapper.createObjectNode(),
            ACTOR_ID,
            NOW);
    event.markRunning();
    event = eventRepository.save(event);

    // 8. Activate START node (completes immediately to outcome 'STARTED')
    completedExecution =
        activationService.activate(
            ActivationRequest.root(
                eventId,
                startNodeId,
                UUID.randomUUID(),
                new CorrelationId(uuidGenerator.generate()),
                new CommandId(uuidGenerator.generate())));

    completedExecution = executionRepository.findById(completedExecution.getId()).orElseThrow();
  }

  @Test
  void routingDecision_persisted_withSelectedEdge() {
    RoutingResult result =
        routingService.route(
            completedExecution.getId(),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    Optional<RoutingDecision> decision =
        decisionRepository.findBySourceNodeExecutionId(completedExecution.getId());
    assertThat(decision).isPresent();
    assertThat(decision.get().getEventId()).isEqualTo(event.getId());
    assertThat(decision.get().getRoutingMode()).isNotBlank();
    assertThat(result.selectedEdgeIds()).containsExactly(edgeId);
  }

  @Test
  void activationToken_persisted_andActivated() {
    routingService.route(
        completedExecution.getId(),
        new CorrelationId(uuidGenerator.generate()),
        new CommandId(uuidGenerator.generate()));

    RoutingDecision decision =
        decisionRepository.findBySourceNodeExecutionId(completedExecution.getId()).orElseThrow();

    List<ActivationToken> tokens = tokenRepository.findAllByRoutingDecisionId(decision.getId());
    assertThat(tokens).isNotEmpty();
    assertThat(tokens).allMatch(t -> t.getStatus() == ActivationTokenStatus.ACTIVATED);
    assertThat(tokens).allMatch(t -> t.getEdgeId().equals(edgeId));
  }

  @Test
  void crashRecovery_duplicateRoute_replayFromTokens_doesNotCreateSecondDecision() {
    routingService.route(
        completedExecution.getId(),
        new CorrelationId(uuidGenerator.generate()),
        new CommandId(uuidGenerator.generate()));

    long decisionCountAfterFirst = decisionRepository.count();

    routingService.route(
        completedExecution.getId(),
        new CorrelationId(uuidGenerator.generate()),
        new CommandId(uuidGenerator.generate()));

    long decisionCountAfterSecond = decisionRepository.count();
    assertThat(decisionCountAfterSecond).isEqualTo(decisionCountAfterFirst);
  }

  @Test
  void activationToken_uniqueKey_duplicateTokenNotCreated() {
    routingService.route(
        completedExecution.getId(),
        new CorrelationId(uuidGenerator.generate()),
        new CommandId(uuidGenerator.generate()));

    long tokenCountAfterFirst = tokenRepository.count();

    routingService.route(
        completedExecution.getId(),
        new CorrelationId(uuidGenerator.generate()),
        new CommandId(uuidGenerator.generate()));

    assertThat(tokenRepository.count()).isEqualTo(tokenCountAfterFirst);
  }
}
