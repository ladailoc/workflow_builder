package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.join.domain.JoinState;
import com.fpt.workflow.runtime.join.repository.JoinArrivedBranchRepository;
import com.fpt.workflow.runtime.join.repository.JoinStateRepository;
import com.fpt.workflow.runtime.join.service.JoinService;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
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
 * P36: Verifies Join node synchronization: - Inbound branches arrive at Join with their
 * join_scope_id. - Join maintains durable join state in join_states and join_arrived_branches. -
 * AND join: waits for all incoming branches matching join_scope_id. - FIRST / XOR join: first
 * branch continues, subsequent branches synchronize/discard without re-routing. - N_OF_M join:
 * routes when threshold reached, late branches do not re-trigger downstream. - While waiting, Join
 * node execution is in WAITING status (wait_reason = JOIN). - Single downstream continuation
 * triggered once threshold is reached. - Replay idempotency: duplicate branch arrival is ignored.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class JoinIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_join_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private RoutingService routingService;
  @Autowired private NodeActivationService activationService;
  @Autowired private JoinService joinService;
  @Autowired private JoinStateRepository joinStateRepository;
  @Autowired private JoinArrivedBranchRepository branchRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private NodeExecutionRepository executionRepository;
  @Autowired private UuidGenerator uuidGenerator;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");

  @BeforeEach
  void setup() {
    jdbcTemplate.execute(
        "TRUNCATE join_arrived_branches, join_states, activation_tokens, routing_decisions, node_executions, events,"
            + " workflow_edges, workflow_nodes, workflow_versions, workflow_definitions,"
            + " ticket_revisions, tickets, request_types RESTART IDENTITY CASCADE");
  }

  private record JoinFixture(
      UUID versionId,
      UUID startNodeId,
      UUID branchANodeId,
      UUID branchBNodeId,
      UUID joinNodeId,
      UUID endNodeId,
      Event event,
      NodeExecution startExecution) {}

  private JoinFixture createJoinFixture(String policy, Integer threshold) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID nodeAId = UUID.randomUUID();
    UUID nodeBId = UUID.randomUUID();
    UUID joinId = UUID.randomUUID();
    UUID endId = UUID.randomUUID();
    UUID edgeAId = UUID.randomUUID();
    UUID edgeBId = UUID.randomUUID();
    UUID edgeJoinAId = UUID.randomUUID();
    UUID edgeJoinBId = UUID.randomUUID();
    UUID edgeEndId = UUID.randomUUID();
    UUID reqId = UUID.randomUUID();
    UUID tId = UUID.randomUUID();
    UUID revId = UUID.randomUUID();

    // 1. Definition
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Join Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        "workflow-join-" + suffix,
        ACTOR_ID,
        ACTOR_ID);

    // 2. Draft Version
    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        verId,
        defId,
        ACTOR_ID);

    // 3. START node with ALL_OUTGOING
    ObjectNode startConfig = objectMapper.createObjectNode();
    startConfig.put("routingMode", "ALL_OUTGOING");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start Split', 1, ?::jsonb, '{}'::jsonb)",
        startId,
        verId,
        startConfig.toString());

    // 4. Branch A & B nodes (REVIEW nodes)
    ObjectNode reviewConfig = objectMapper.createObjectNode();
    reviewConfig.putArray("allowedActions").add("SUBMIT");
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

    // 5. JOIN node
    ObjectNode joinConfig = objectMapper.createObjectNode();
    joinConfig.put("policy", policy);
    if (threshold != null) {
      joinConfig.put("threshold", threshold);
    }

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'join', 'JOIN', 'Join Node', 1, ?::jsonb, '{}'::jsonb)",
        joinId,
        verId,
        joinConfig.toString());

    // 6. END node
    ObjectNode endConfig = objectMapper.createObjectNode();
    endConfig.put("outcome", "APPROVED");

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'end', 'END', 'End Node', 1, ?::jsonb, '{}'::jsonb)",
        endId,
        verId,
        endConfig.toString());

    // 7. Edges:
    // start -> branch_a, branch_b
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

    // branch_a -> join
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'SUBMITTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeJoinAId,
        verId,
        nodeAId,
        joinId);

    // branch_b -> join
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'SUBMITTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeJoinBId,
        verId,
        nodeBId,
        joinId);

    // join -> end
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'DEFAULT', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeEndId,
        verId,
        joinId,
        endId);

    // 8. Publish
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', "
            + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() "
            + "WHERE id = ?",
        ACTOR_ID,
        verId);

    // 9. Ticket
    jdbcTemplate.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Join Request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        reqId,
        "req-join-" + suffix,
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
            "join-corr-" + suffix,
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

    return new JoinFixture(verId, startId, nodeAId, nodeBId, joinId, endId, ev, startExecution);
  }

  @Test
  void andJoin_waitsForAllInboundBranches_thenCompletesAndRoutes() {
    JoinFixture f = createJoinFixture("AND", null);
    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    // 1. Trigger split from START -> activates branch A and branch B
    RoutingResult splitResult = routingService.route(f.startExecution().getId(), corr, cmd);
    assertThat(splitResult.activations()).hasSize(2);

    NodeExecution branchA =
        splitResult.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(f.branchANodeId()))
            .findFirst()
            .orElseThrow();

    NodeExecution branchB =
        splitResult.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(f.branchBNodeId()))
            .findFirst()
            .orElseThrow();

    UUID joinScopeId = branchA.getJoinScopeId();
    assertThat(joinScopeId).isNotNull();

    // 2. Complete Branch A only
    branchA.complete("SUBMITTED", objectMapper.createObjectNode(), NOW);
    executionRepository.saveAndFlush(branchA);

    RoutingResult routeA = routingService.route(branchA.getId(), corr, cmd);
    assertThat(routeA.activations()).hasSize(1);
    NodeExecution joinExecA = routeA.activations().getFirst();

    // Join execution is in WAITING state on JOIN
    assertThat(joinExecA.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);
    assertThat(joinExecA.getWaitReason()).isEqualTo(RuntimeWaitReason.JOIN);

    // JoinState has arrivedCount = 1, requiredCount = 2, status = WAITING
    JoinState state =
        joinStateRepository
            .findByEventIdAndNodeDefinitionIdAndJoinScopeId(
                f.event().getId(), f.joinNodeId(), joinScopeId)
            .orElseThrow();
    assertThat(state.getArrivedCount()).isEqualTo(1);
    assertThat(state.getRequiredCount()).isEqualTo(2);
    assertThat(state.getStatus()).isEqualTo("WAITING");
    assertThat(state.isRoutedDownstream()).isFalse();

    // Event is in WAITING state
    Event currentEvent = eventRepository.findById(f.event().getId()).orElseThrow();
    assertThat(currentEvent.getStatus()).isEqualTo(EventStatus.WAITING);
    assertThat(currentEvent.getWaitReason())
        .isIn(RuntimeWaitReason.JOIN, RuntimeWaitReason.HUMAN_TASK);

    // End node NOT yet created
    List<NodeExecution> allExecs =
        executionRepository.findAllByEventIdOrderByCreatedAtAsc(f.event().getId());
    assertThat(allExecs).noneMatch(e -> e.getNodeDefinitionId().equals(f.endNodeId()));

    // 3. Complete Branch B (second and final inbound branch)
    branchB.complete("SUBMITTED", objectMapper.createObjectNode(), NOW);
    executionRepository.saveAndFlush(branchB);

    RoutingResult routeB = routingService.route(branchB.getId(), corr, cmd);
    assertThat(routeB.activations()).hasSize(1);

    // JoinState is now COMPLETED, routed_downstream = true
    JoinState updatedState =
        joinStateRepository
            .findByEventIdAndNodeDefinitionIdAndJoinScopeId(
                f.event().getId(), f.joinNodeId(), joinScopeId)
            .orElseThrow();
    assertThat(updatedState.getArrivedCount()).isEqualTo(2);
    assertThat(updatedState.getStatus()).isEqualTo("COMPLETED");
    assertThat(updatedState.isRoutedDownstream()).isTrue();

    // Join node execution is COMPLETED
    NodeExecution completedJoin = executionRepository.findById(joinExecA.getId()).orElseThrow();
    assertThat(completedJoin.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);

    // Single downstream continuation: END node was activated and completed!
    List<NodeExecution> finalExecs =
        executionRepository.findAllByEventIdOrderByCreatedAtAsc(f.event().getId());
    List<NodeExecution> endExecs =
        finalExecs.stream().filter(e -> e.getNodeDefinitionId().equals(f.endNodeId())).toList();
    assertThat(endExecs).hasSize(1);
    assertThat(endExecs.getFirst().getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
  }

  @Test
  void firstJoin_routesOnFirstBranch_andDoesNotDuplicateDownstreamOnLateBranch() {
    JoinFixture f = createJoinFixture("FIRST", null);
    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    // 1. Split
    RoutingResult splitResult = routingService.route(f.startExecution().getId(), corr, cmd);
    NodeExecution branchA =
        splitResult.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(f.branchANodeId()))
            .findFirst()
            .orElseThrow();

    NodeExecution branchB =
        splitResult.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(f.branchBNodeId()))
            .findFirst()
            .orElseThrow();

    UUID joinScopeId = branchA.getJoinScopeId();

    // 2. Complete Branch A -> FIRST condition (arrived >= 1) satisfied immediately!
    branchA.complete("SUBMITTED", objectMapper.createObjectNode(), NOW);
    executionRepository.saveAndFlush(branchA);

    RoutingResult routeA = routingService.route(branchA.getId(), corr, cmd);
    assertThat(routeA.activations()).hasSize(1);

    JoinState state =
        joinStateRepository
            .findByEventIdAndNodeDefinitionIdAndJoinScopeId(
                f.event().getId(), f.joinNodeId(), joinScopeId)
            .orElseThrow();
    assertThat(state.getStatus()).isEqualTo("COMPLETED");
    assertThat(state.isRoutedDownstream()).isTrue();

    // Downstream END node is activated
    List<NodeExecution> execsAfterA =
        executionRepository.findAllByEventIdOrderByCreatedAtAsc(f.event().getId());
    assertThat(execsAfterA).anyMatch(e -> e.getNodeDefinitionId().equals(f.endNodeId()));

    // 3. Late arriving Branch B completes
    branchB.complete("SUBMITTED", objectMapper.createObjectNode(), NOW);
    executionRepository.saveAndFlush(branchB);

    RoutingResult routeB = routingService.route(branchB.getId(), corr, cmd);

    // Arrived branches records both
    JoinState updatedState =
        joinStateRepository
            .findByEventIdAndNodeDefinitionIdAndJoinScopeId(
                f.event().getId(), f.joinNodeId(), joinScopeId)
            .orElseThrow();
    assertThat(updatedState.getArrivedCount()).isEqualTo(2);

    // But END node is NOT duplicated! Exactly 1 END node execution exists!
    List<NodeExecution> finalExecs =
        executionRepository.findAllByEventIdOrderByCreatedAtAsc(f.event().getId());
    long endCount =
        finalExecs.stream().filter(e -> e.getNodeDefinitionId().equals(f.endNodeId())).count();
    assertThat(endCount).isEqualTo(1);
  }

  @Test
  void nOfMJoin_routesWhenThresholdReached() {
    // 3 branches, threshold = 2
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID nodeAId = UUID.randomUUID();
    UUID nodeBId = UUID.randomUUID();
    UUID nodeCId = UUID.randomUUID();
    UUID joinId = UUID.randomUUID();
    UUID endId = UUID.randomUUID();
    UUID reqId = UUID.randomUUID();
    UUID tId = UUID.randomUUID();
    UUID revId = UUID.randomUUID();

    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'N-of-M Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        "workflow-nofm-" + suffix,
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
            + "VALUES (?, ?, 'start', 'START', 'Start Split', 1, ?::jsonb, '{}'::jsonb)",
        startId,
        verId,
        startConfig.toString());

    ObjectNode reviewConfig = objectMapper.createObjectNode();
    reviewConfig.putArray("allowedActions").add("SUBMIT");
    reviewConfig
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", ACTOR_ID.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_a', 'REVIEW', 'A', 1, ?::jsonb, '{}'::jsonb)",
        nodeAId,
        verId,
        reviewConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_b', 'REVIEW', 'B', 1, ?::jsonb, '{}'::jsonb)",
        nodeBId,
        verId,
        reviewConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'branch_c', 'REVIEW', 'C', 1, ?::jsonb, '{}'::jsonb)",
        nodeCId,
        verId,
        reviewConfig.toString());

    ObjectNode joinConfig = objectMapper.createObjectNode();
    joinConfig.put("policy", "N_OF_M");
    joinConfig.put("threshold", 2);

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'join', 'JOIN', 'Join', 1, ?::jsonb, '{}'::jsonb)",
        joinId,
        verId,
        joinConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'end', 'END', 'End', 1, '{\"outcome\":\"APPROVED\"}'::jsonb, '{}'::jsonb)",
        endId,
        verId);

    // Edges start -> A, B, C
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        UUID.randomUUID(),
        verId,
        startId,
        nodeAId);
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) VALUES (?, ?, ?, 'STARTED', ?, 1, true, 'CONDITIONAL', '{}'::jsonb)",
        UUID.randomUUID(),
        verId,
        startId,
        nodeBId);
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) VALUES (?, ?, ?, 'STARTED', ?, 2, true, 'CONDITIONAL', '{}'::jsonb)",
        UUID.randomUUID(),
        verId,
        startId,
        nodeCId);

    // Edges A, B, C -> join
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) VALUES (?, ?, ?, 'SUBMITTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        UUID.randomUUID(),
        verId,
        nodeAId,
        joinId);
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) VALUES (?, ?, ?, 'SUBMITTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        UUID.randomUUID(),
        verId,
        nodeBId,
        joinId);
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) VALUES (?, ?, ?, 'SUBMITTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        UUID.randomUUID(),
        verId,
        nodeCId,
        joinId);

    // join -> end
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) VALUES (?, ?, ?, 'DEFAULT', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        UUID.randomUUID(),
        verId,
        joinId,
        endId);

    // Publish
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() WHERE id = ?",
        ACTOR_ID,
        verId);

    jdbcTemplate.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, creation_policy_json, created_at, updated_at) VALUES (?, ?, 'N-of-M', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        reqId,
        "req-nofm-" + suffix,
        defId);
    jdbcTemplate.update(
        "INSERT INTO tickets (id, request_type_id, creator_id, status, data_json, created_at, updated_at) VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, now(), now())",
        tId,
        reqId,
        ACTOR_ID);
    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id, ticket_id, revision_no, data_snapshot_json, source_schema_version, schema_checksum, submitted_by, submitted_at) VALUES (?, ?, 1, '{}'::jsonb, 'v1', 'checksum', ?, now())",
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
            "nofm-corr-" + suffix,
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

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    RoutingResult split = routingService.route(startExecution.getId(), corr, cmd);
    assertThat(split.activations()).hasSize(3);

    NodeExecution bA =
        split.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(nodeAId))
            .findFirst()
            .orElseThrow();
    NodeExecution bB =
        split.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(nodeBId))
            .findFirst()
            .orElseThrow();
    NodeExecution bC =
        split.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(nodeCId))
            .findFirst()
            .orElseThrow();

    UUID joinScopeId = bA.getJoinScopeId();

    // 1. Branch A arrives (1/2) -> WAITING
    bA.complete("SUBMITTED", objectMapper.createObjectNode(), NOW);
    executionRepository.saveAndFlush(bA);
    routingService.route(bA.getId(), corr, cmd);

    JoinState s1 =
        joinStateRepository
            .findByEventIdAndNodeDefinitionIdAndJoinScopeId(ev.getId(), joinId, joinScopeId)
            .orElseThrow();
    assertThat(s1.getArrivedCount()).isEqualTo(1);
    assertThat(s1.getStatus()).isEqualTo("WAITING");

    // 2. Branch B arrives (2/2) -> threshold met, routes downstream!
    bB.complete("SUBMITTED", objectMapper.createObjectNode(), NOW);
    executionRepository.saveAndFlush(bB);
    routingService.route(bB.getId(), corr, cmd);

    JoinState s2 =
        joinStateRepository
            .findByEventIdAndNodeDefinitionIdAndJoinScopeId(ev.getId(), joinId, joinScopeId)
            .orElseThrow();
    assertThat(s2.getArrivedCount()).isEqualTo(2);
    assertThat(s2.getStatus()).isEqualTo("COMPLETED");

    // END node activated
    List<NodeExecution> afterB =
        executionRepository.findAllByEventIdOrderByCreatedAtAsc(ev.getId());
    assertThat(afterB).anyMatch(e -> e.getNodeDefinitionId().equals(endId));

    // 3. Late Branch C arrives (3/2) -> recorded, does not duplicate downstream
    bC.complete("SUBMITTED", objectMapper.createObjectNode(), NOW);
    executionRepository.saveAndFlush(bC);
    routingService.route(bC.getId(), corr, cmd);

    JoinState s3 =
        joinStateRepository
            .findByEventIdAndNodeDefinitionIdAndJoinScopeId(ev.getId(), joinId, joinScopeId)
            .orElseThrow();
    assertThat(s3.getArrivedCount()).isEqualTo(3);

    List<NodeExecution> afterC =
        executionRepository.findAllByEventIdOrderByCreatedAtAsc(ev.getId());
    long endCount = afterC.stream().filter(e -> e.getNodeDefinitionId().equals(endId)).count();
    assertThat(endCount).isEqualTo(1);
  }

  @Test
  void duplicateBranchArrival_isIdempotent() {
    JoinFixture f = createJoinFixture("AND", null);
    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    RoutingResult splitResult = routingService.route(f.startExecution().getId(), corr, cmd);
    NodeExecution branchA =
        splitResult.activations().stream()
            .filter(e -> e.getNodeDefinitionId().equals(f.branchANodeId()))
            .findFirst()
            .orElseThrow();

    UUID joinScopeId = branchA.getJoinScopeId();

    branchA.complete("SUBMITTED", objectMapper.createObjectNode(), NOW);
    executionRepository.saveAndFlush(branchA);

    // First arrival
    routingService.route(branchA.getId(), corr, cmd);

    // Duplicate replay arrival of Branch A
    routingService.route(branchA.getId(), corr, cmd);

    JoinState state =
        joinStateRepository
            .findByEventIdAndNodeDefinitionIdAndJoinScopeId(
                f.event().getId(), f.joinNodeId(), joinScopeId)
            .orElseThrow();

    // Arrived count is 1, not 2
    assertThat(state.getArrivedCount()).isEqualTo(1);
  }
}
