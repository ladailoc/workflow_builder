package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingMode;
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
 * P35: Verifies Parallel Split (RoutingMode.ALL_OUTGOING): - Evaluates all outgoing transitions
 * from the split node. - Every branch activates concurrently with a unique split_scope_id and
 * join_scope_id. - Nested path_token isolates branch variable and output scope (parentPath + "/" +
 * edgePrefix). - Activation tokens durable in activation_tokens table. - Replay idempotency:
 * re-evaluating split does not duplicate tokens or executions. - Nested parallel splits maintain
 * distinct scopes and deep path tokens.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class ParallelSplitIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_split_test")
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
  private static final Instant NOW = Instant.parse("2026-09-08T07:00:00Z");

  @BeforeEach
  void setup() {
    jdbcTemplate.execute(
        "TRUNCATE activation_tokens, routing_decisions, node_executions, events,"
            + " workflow_edges, workflow_nodes, workflow_versions, workflow_definitions,"
            + " ticket_revisions, tickets, request_types RESTART IDENTITY CASCADE");
  }

  private record TwoBranchFixture(
      UUID versionId,
      UUID startNodeId,
      UUID branchANodeId,
      UUID branchBNodeId,
      UUID edgeAId,
      UUID edgeBId,
      Event event,
      NodeExecution startExecution) {}

  private TwoBranchFixture createTwoBranchFixture() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID nodeAId = UUID.randomUUID();
    UUID nodeBId = UUID.randomUUID();
    UUID edgeAId = UUID.randomUUID();
    UUID edgeBId = UUID.randomUUID();
    UUID reqId = UUID.randomUUID();
    UUID tId = UUID.randomUUID();
    UUID revId = UUID.randomUUID();

    // 1. Definition
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Split Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        "workflow-split-" + suffix,
        ACTOR_ID,
        ACTOR_ID);

    // 2. Draft Version
    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        verId,
        defId,
        ACTOR_ID);

    // 3. START node configured with routingMode: ALL_OUTGOING
    ObjectNode startConfig = objectMapper.createObjectNode();
    startConfig.put("routingMode", "ALL_OUTGOING");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start Split', 1, ?::jsonb, '{}'::jsonb)",
        startId,
        verId,
        startConfig.toString());

    // 4. Branch A and Branch B nodes
    ObjectNode reviewConfig = objectMapper.createObjectNode();
    reviewConfig.putArray("allowedActions").add("APPROVE").add("REJECT");
    reviewConfig
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", ACTOR_ID.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_a', 'REVIEW', 'Branch A Review', 1, ?::jsonb, '{}'::jsonb)",
        nodeAId,
        verId,
        reviewConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_b', 'REVIEW', 'Branch B Review', 1, ?::jsonb, '{}'::jsonb)",
        nodeBId,
        verId,
        reviewConfig.toString());

    // 5. Outgoing edges from START on port STARTED to Branch A and Branch B
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeAId,
        verId,
        startId,
        nodeAId);

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 1, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeBId,
        verId,
        startId,
        nodeBId);

    // 6. Publish Version
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', "
            + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() "
            + "WHERE id = ?",
        ACTOR_ID,
        verId);

    // 7. Request Type, Ticket, Revision
    jdbcTemplate.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Split Request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        reqId,
        "req-split-" + suffix,
        defId);

    jdbcTemplate.update(
        "INSERT INTO tickets (id, request_type_id, creator_id, status, data_json, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, now(), now())",
        tId,
        reqId,
        ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id, ticket_id, revision_no, data_snapshot_json, source_schema_version, schema_checksum, submitted_by, submitted_at) "
            + "VALUES (?, ?, 1, '{}'::jsonb, 'v1', 'checksum', ?, now())",
        revId,
        tId,
        ACTOR_ID);

    jdbcTemplate.update(
        "UPDATE tickets SET status = 'SUBMITTED', data_revision = 1, current_revision_id = ?, submitted_at = now(), updated_at = now() WHERE id = ?",
        revId,
        tId);

    Event ev =
        Event.createRoot(
            uuidGenerator.generate(),
            tId,
            verId,
            revId,
            null,
            null,
            "USER_SUBMIT",
            "split-corr-" + suffix,
            objectMapper.createObjectNode(),
            ACTOR_ID,
            NOW);
    ev.markRunning();
    ev = eventRepository.save(ev);

    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                ev.getId(),
                startId,
                UUID.randomUUID(),
                new CorrelationId(uuidGenerator.generate()),
                new CommandId(uuidGenerator.generate())));

    return new TwoBranchFixture(
        verId, startId, nodeAId, nodeBId, edgeAId, edgeBId, ev, startExecution);
  }

  @Test
  void parallelSplit_twoBranches_activatesBothWithSharedScopeAndIsolatedPaths() {
    TwoBranchFixture f = createTwoBranchFixture();
    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    RoutingResult result = routingService.route(f.startExecution().getId(), corr, cmd);

    assertThat(result.mode()).isEqualTo(RoutingMode.ALL_OUTGOING);
    assertThat(result.selectedEdgeIds()).containsExactlyInAnyOrder(f.edgeAId(), f.edgeBId());
    assertThat(result.activations()).hasSize(2);

    NodeExecution execA =
        result.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(f.branchANodeId()))
            .findFirst()
            .orElseThrow();

    NodeExecution execB =
        result.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(f.branchBNodeId()))
            .findFirst()
            .orElseThrow();

    // 1. Both branches share the same split_scope_id and join_scope_id
    assertThat(execA.getSplitScopeId()).isNotNull();
    assertThat(execA.getJoinScopeId()).isNotNull();
    assertThat(execA.getSplitScopeId()).isEqualTo(execB.getSplitScopeId());
    assertThat(execA.getJoinScopeId()).isEqualTo(execB.getJoinScopeId());

    // 2. Each branch has its own nested path_token based on edge ID
    String edgeAPrefix = f.edgeAId().toString().substring(0, 8);
    String edgeBPrefix = f.edgeBId().toString().substring(0, 8);
    assertThat(execA.getPathToken()).isEqualTo("root/" + edgeAPrefix);
    assertThat(execB.getPathToken()).isEqualTo("root/" + edgeBPrefix);
    assertThat(execA.getPathToken()).isNotEqualTo(execB.getPathToken());

    // 3. Activation tokens are persisted with ACTIVATED status and match scopes
    List<ActivationToken> tokens =
        tokenRepository.findAllByEventIdAndStatus(
            f.event().getId(), ActivationTokenStatus.ACTIVATED);
    assertThat(tokens).hasSize(2);
    assertThat(tokens).allMatch(t -> t.getSplitScopeId().equals(execA.getSplitScopeId()));
    assertThat(tokens).allMatch(t -> t.getJoinScopeId().equals(execA.getJoinScopeId()));

    // 4. RoutingDecision is recorded
    RoutingDecision decision =
        decisionRepository.findBySourceNodeExecutionId(f.startExecution().getId()).orElseThrow();
    assertThat(decision.getRoutingMode()).isEqualTo("ALL_OUTGOING");
    assertThat(decision.getSelectedEdgeIdsJson()).hasSize(2);
  }

  @Test
  void parallelSplit_threeBranches_activatesAllBranches() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID nodeAId = UUID.randomUUID();
    UUID nodeBId = UUID.randomUUID();
    UUID nodeCId = UUID.randomUUID();
    UUID edgeAId = UUID.randomUUID();
    UUID edgeBId = UUID.randomUUID();
    UUID edgeCId = UUID.randomUUID();
    UUID reqId = UUID.randomUUID();
    UUID tId = UUID.randomUUID();
    UUID revId = UUID.randomUUID();

    // 1. Definition
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Split 3 Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        "workflow-split3-" + suffix,
        ACTOR_ID,
        ACTOR_ID);

    // 2. Draft Version
    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        verId,
        defId,
        ACTOR_ID);

    // 3. START node
    ObjectNode startConfig = objectMapper.createObjectNode();
    startConfig.put("routingMode", "ALL_OUTGOING");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start Split', 1, ?::jsonb, '{}'::jsonb)",
        startId,
        verId,
        startConfig.toString());

    // 4. 3 Branch nodes
    ObjectNode reviewConfig = objectMapper.createObjectNode();
    reviewConfig.putArray("allowedActions").add("APPROVE");
    reviewConfig
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", ACTOR_ID.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_a', 'REVIEW', 'Branch A Review', 1, ?::jsonb, '{}'::jsonb)",
        nodeAId,
        verId,
        reviewConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_b', 'REVIEW', 'Branch B Review', 1, ?::jsonb, '{}'::jsonb)",
        nodeBId,
        verId,
        reviewConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_c', 'REVIEW', 'Branch C Review', 1, ?::jsonb, '{}'::jsonb)",
        nodeCId,
        verId,
        reviewConfig.toString());

    // 5. 3 Outgoing edges
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeAId,
        verId,
        startId,
        nodeAId);

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 1, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeBId,
        verId,
        startId,
        nodeBId);

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 2, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeCId,
        verId,
        startId,
        nodeCId);

    // 6. Publish Version
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', "
            + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() "
            + "WHERE id = ?",
        ACTOR_ID,
        verId);

    // 7. Request Type, Ticket, Revision
    jdbcTemplate.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Split 3 Request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        reqId,
        "req-split3-" + suffix,
        defId);

    jdbcTemplate.update(
        "INSERT INTO tickets (id, request_type_id, creator_id, status, data_json, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, now(), now())",
        tId,
        reqId,
        ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id, ticket_id, revision_no, data_snapshot_json, source_schema_version, schema_checksum, submitted_by, submitted_at) "
            + "VALUES (?, ?, 1, '{}'::jsonb, 'v1', 'checksum', ?, now())",
        revId,
        tId,
        ACTOR_ID);

    jdbcTemplate.update(
        "UPDATE tickets SET status = 'SUBMITTED', data_revision = 1, current_revision_id = ?, submitted_at = now(), updated_at = now() WHERE id = ?",
        revId,
        tId);

    Event ev =
        Event.createRoot(
            uuidGenerator.generate(),
            tId,
            verId,
            revId,
            null,
            null,
            "USER_SUBMIT",
            "split3-corr-" + suffix,
            objectMapper.createObjectNode(),
            ACTOR_ID,
            NOW);
    ev.markRunning();
    ev = eventRepository.save(ev);

    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                ev.getId(),
                startId,
                UUID.randomUUID(),
                new CorrelationId(uuidGenerator.generate()),
                new CommandId(uuidGenerator.generate())));

    // Route from START node
    RoutingResult result =
        routingService.route(
            startExecution.getId(),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    assertThat(result.selectedEdgeIds()).hasSize(3);
    assertThat(result.activations()).hasSize(3);

    // All 3 share the same split_scope_id
    UUID commonSplitScope = result.activations().getFirst().getSplitScopeId();
    assertThat(result.activations()).allMatch(e -> commonSplitScope.equals(e.getSplitScopeId()));

    // All 3 have unique path tokens
    List<String> pathTokens =
        result.activations().stream().map(NodeExecution::getPathToken).toList();
    assertThat(pathTokens).doesNotHaveDuplicates().hasSize(3);
  }

  @Test
  void parallelSplit_replayIdempotency_doesNotDuplicateTokensOrExecutions() {
    TwoBranchFixture f = createTwoBranchFixture();
    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    RoutingResult first = routingService.route(f.startExecution().getId(), corr, cmd);
    assertThat(first.activations()).hasSize(2);

    // Re-route same split node
    RoutingResult replay = routingService.route(f.startExecution().getId(), corr, cmd);
    assertThat(replay.activations()).hasSize(2);
    assertThat(replay.selectedEdgeIds()).isEqualTo(first.selectedEdgeIds());

    // No duplicate node executions in DB
    List<NodeExecution> allExecutions =
        executionRepository.findAllByEventIdOrderByCreatedAtAsc(f.event().getId());
    // 1 start execution + 2 branch executions = 3 total
    assertThat(allExecutions).hasSize(3);

    // No duplicate tokens in DB
    List<ActivationToken> allTokens =
        tokenRepository.findAllByEventIdAndStatus(
            f.event().getId(), ActivationTokenStatus.ACTIVATED);
    assertThat(allTokens).hasSize(2);

    // Exactly 1 decision in DB
    assertThat(decisionRepository.findBySourceNodeExecutionId(f.startExecution().getId()))
        .isPresent();
  }

  @Test
  void nestedParallelSplit_maintainsIsolatedScopesAndDeepPaths() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID nodeAId = UUID.randomUUID();
    UUID nodeBId = UUID.randomUUID();
    UUID nodeA1Id = UUID.randomUUID();
    UUID nodeA2Id = UUID.randomUUID();
    UUID edgeAId = UUID.randomUUID();
    UUID edgeBId = UUID.randomUUID();
    UUID edgeA1Id = UUID.randomUUID();
    UUID edgeA2Id = UUID.randomUUID();
    UUID reqId = UUID.randomUUID();
    UUID tId = UUID.randomUUID();
    UUID revId = UUID.randomUUID();

    // 1. Definition
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Nested Split Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        "workflow-nested-" + suffix,
        ACTOR_ID,
        ACTOR_ID);

    // 2. Draft Version
    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        verId,
        defId,
        ACTOR_ID);

    // 3. START node (ALL_OUTGOING)
    ObjectNode startConfig = objectMapper.createObjectNode();
    startConfig.put("routingMode", "ALL_OUTGOING");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start Split', 1, ?::jsonb, '{}'::jsonb)",
        startId,
        verId,
        startConfig.toString());

    // 4. Branch A (ALL_OUTGOING) and Branch B
    ObjectNode branchAConfig = objectMapper.createObjectNode();
    branchAConfig.put("routingMode", "ALL_OUTGOING");
    branchAConfig.putArray("allowedActions").add("SUBMIT");
    branchAConfig
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", ACTOR_ID.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_a', 'REVIEW', 'Branch A Review', 1, ?::jsonb, '{}'::jsonb)",
        nodeAId,
        verId,
        branchAConfig.toString());

    ObjectNode branchBConfig = objectMapper.createObjectNode();
    branchBConfig.putArray("allowedActions").add("SUBMIT");
    branchBConfig
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", ACTOR_ID.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_b', 'REVIEW', 'Branch B Review', 1, ?::jsonb, '{}'::jsonb)",
        nodeBId,
        verId,
        branchBConfig.toString());

    // 5. Leaves A1 and A2
    ObjectNode leafConfig = objectMapper.createObjectNode();
    leafConfig.put("outcome", "APPROVED");

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_a1', 'END', 'End A1', 1, ?::jsonb, '{}'::jsonb)",
        nodeA1Id,
        verId,
        leafConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_a2', 'END', 'End A2', 1, ?::jsonb, '{}'::jsonb)",
        nodeA2Id,
        verId,
        leafConfig.toString());

    // 6. Edges: START -> branch_a, branch_b
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeAId,
        verId,
        startId,
        nodeAId);

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 1, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeBId,
        verId,
        startId,
        nodeBId);

    // Edges: branch_a -> branch_a1, branch_a2
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'SUBMITTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeA1Id,
        verId,
        nodeAId,
        nodeA1Id);

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'SUBMITTED', ?, 1, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeA2Id,
        verId,
        nodeAId,
        nodeA2Id);

    // 7. Publish Version
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', "
            + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() "
            + "WHERE id = ?",
        ACTOR_ID,
        verId);

    // 8. Request Type, Ticket, Revision
    jdbcTemplate.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Nested Split Request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        reqId,
        "req-nested-" + suffix,
        defId);

    jdbcTemplate.update(
        "INSERT INTO tickets (id, request_type_id, creator_id, status, data_json, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, now(), now())",
        tId,
        reqId,
        ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id, ticket_id, revision_no, data_snapshot_json, source_schema_version, schema_checksum, submitted_by, submitted_at) "
            + "VALUES (?, ?, 1, '{}'::jsonb, 'v1', 'checksum', ?, now())",
        revId,
        tId,
        ACTOR_ID);

    jdbcTemplate.update(
        "UPDATE tickets SET status = 'SUBMITTED', data_revision = 1, current_revision_id = ?, submitted_at = now(), updated_at = now() WHERE id = ?",
        revId,
        tId);

    Event ev =
        Event.createRoot(
            uuidGenerator.generate(),
            tId,
            verId,
            revId,
            null,
            null,
            "USER_SUBMIT",
            "nested-corr-" + suffix,
            objectMapper.createObjectNode(),
            ACTOR_ID,
            NOW);
    ev.markRunning();
    ev = eventRepository.save(ev);

    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(
                ev.getId(),
                startId,
                UUID.randomUUID(),
                new CorrelationId(uuidGenerator.generate()),
                new CommandId(uuidGenerator.generate())));

    // 1. Initial split from START
    RoutingResult split1 =
        routingService.route(
            startExecution.getId(),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    NodeExecution execA =
        split1.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(nodeAId))
            .findFirst()
            .orElseThrow();

    UUID split1Scope = execA.getSplitScopeId();
    String pathA = execA.getPathToken(); // e.g. root/edgeA

    // 2. Complete Branch A occurrence and trigger nested split
    execA.complete("SUBMITTED", objectMapper.createObjectNode(), NOW);
    executionRepository.saveAndFlush(execA);

    RoutingResult split2 =
        routingService.route(
            execA.getId(),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    assertThat(split2.mode()).isEqualTo(RoutingMode.ALL_OUTGOING);
    assertThat(split2.activations()).hasSize(2);

    NodeExecution execA1 = split2.activations().get(0);
    NodeExecution execA2 = split2.activations().get(1);

    // 3. Nested split scope is distinct from parent split scope
    assertThat(execA1.getSplitScopeId()).isNotEqualTo(split1Scope);
    assertThat(execA1.getSplitScopeId()).isEqualTo(execA2.getSplitScopeId());

    // 4. Nested path tokens contain the two-level path hierarchy
    String edgeA1Prefix = edgeA1Id.toString().substring(0, 8);
    String edgeA2Prefix = edgeA2Id.toString().substring(0, 8);
    assertThat(execA1.getPathToken()).startsWith(pathA + "/");
    assertThat(execA2.getPathToken()).startsWith(pathA + "/");
    assertThat(execA1.getPathToken()).isEqualTo(pathA + "/" + edgeA1Prefix);
    assertThat(execA2.getPathToken()).isEqualTo(pathA + "/" + edgeA2Prefix);
  }
}
