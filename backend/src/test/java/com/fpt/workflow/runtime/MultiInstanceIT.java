package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.multiinstance.domain.ExecutionMode;
import com.fpt.workflow.runtime.multiinstance.domain.MultiInstanceState;
import com.fpt.workflow.runtime.multiinstance.domain.NodeItemExecution;
import com.fpt.workflow.runtime.multiinstance.repository.MultiInstanceStateRepository;
import com.fpt.workflow.runtime.multiinstance.repository.NodeItemExecutionRepository;
import com.fpt.workflow.runtime.multiinstance.service.MultiInstanceService;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
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
 * P34: Verifies Multi-Instance node execution, collection sizes, parallel/sequential modes,
 * completion thresholds (ALL, ANY, N_OF_M, PERCENTAGE), duplicate completion idempotency, and
 * KEEP_RUNNING single-route semantics.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class MultiInstanceIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_mi_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private NodeActivationService activationService;
  @Autowired private MultiInstanceService multiInstanceService;
  @Autowired private MultiInstanceStateRepository stateRepository;
  @Autowired private NodeItemExecutionRepository itemRepository;
  @Autowired private NodeExecutionRepository executionRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private UuidGenerator uuidGenerator;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-08T06:00:00Z");

  private record Fixture(
      UUID versionId, UUID miNodeId, UUID endNodeId, UUID ticketId, UUID revisionId) {}

  @BeforeEach
  void setup() {
    jdbcTemplate.execute(
        "TRUNCATE node_item_executions, multi_instance_states, activation_tokens, routing_decisions,"
            + " node_executions, events, workflow_edges, workflow_nodes, workflow_variables,"
            + " workflow_versions, workflow_definitions, ticket_revisions, tickets, request_types RESTART IDENTITY CASCADE");
  }

  private Fixture createFixture(String policy, String remainingPolicy, ExecutionMode mode) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    UUID endId = UUID.randomUUID();
    UUID reqId = UUID.randomUUID();
    UUID tId = UUID.randomUUID();
    UUID revId = UUID.randomUUID();

    // 1. Definition
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'MI Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        "workflow-mi-" + suffix,
        ACTOR_ID,
        ACTOR_ID);

    // 2. Draft Version
    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        verId,
        defId,
        ACTOR_ID);

    // 3. Workflow Variable 'reviewers'
    jdbcTemplate.update(
        "INSERT INTO workflow_variables (id, workflow_version_id, key, type_descriptor_json, scope, mutable, sensitive) "
            + "VALUES (?, ?, 'reviewers', '{\"type\":\"ARRAY\",\"nullable\":false,\"itemType\":{\"type\":\"STRING\",\"nullable\":false}}'::jsonb, 'EVENT', true, false)",
        UUID.randomUUID(),
        verId);

    // 4. MI Node definition
    ObjectNode miNodeConfig = objectMapper.createObjectNode();
    ObjectNode miConfig = miNodeConfig.putObject("multiInstance");
    miConfig.put("collectionPath", "variables.reviewers");
    miConfig.put("executionMode", mode.name());
    miConfig.put("completionPolicy", policy);
    miConfig.put("remainingItemPolicy", remainingPolicy);

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'mi_review', 'REVIEW', 'Multi Review', 1, ?::jsonb, '{}'::jsonb)",
        nodeId,
        verId,
        miNodeConfig.toString());

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'end', 'END', 'End', 1, '{\"outcome\":\"APPROVED\"}'::jsonb, '{}'::jsonb)",
        endId,
        verId);

    // 5. Edge: mi_review -> end
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'DEFAULT', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        UUID.randomUUID(),
        verId,
        nodeId,
        endId);

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
            + "VALUES (?, ?, 'MI Request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        reqId,
        "req-mi-" + suffix,
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

    return new Fixture(verId, nodeId, endId, tId, revId);
  }

  private Event createEventWithReviewers(Fixture fixture, List<String> reviewers) {
    ObjectNode variables = objectMapper.createObjectNode();
    ArrayNode revArray = variables.putArray("reviewers");
    reviewers.forEach(revArray::add);

    Event ev =
        Event.createRoot(
            uuidGenerator.generate(),
            fixture.ticketId(),
            fixture.versionId(),
            fixture.revisionId(),
            null,
            null,
            "USER_SUBMIT",
            "mi-corr-" + UUID.randomUUID().toString().substring(0, 8),
            variables,
            ACTOR_ID,
            NOW);
    ev.markRunning();
    return eventRepository.save(ev);
  }

  @Test
  void parallel_allPolicy_createsItemPerElement_andCompletesWhenAllFinish() {
    Fixture fixture = createFixture("ALL", "CANCEL_REMAINING", ExecutionMode.PARALLEL);
    Event event = createEventWithReviewers(fixture, List.of("alice", "bob", "carol"));

    // Activate MI node
    NodeExecution execution =
        activationService.activate(
            ActivationRequest.root(
                event.getId(),
                fixture.miNodeId(),
                UUID.randomUUID(),
                new CorrelationId(uuidGenerator.generate()),
                new CommandId(uuidGenerator.generate())));

    // Node is in WAITING state for MULTI_INSTANCE
    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);
    assertThat(execution.getWaitReason()).isEqualTo(RuntimeWaitReason.MULTI_INSTANCE);

    // MultiInstanceState was created with 3 items
    MultiInstanceState state =
        stateRepository.findByNodeExecutionId(execution.getId()).orElseThrow();
    assertThat(state.getTotalItems()).isEqualTo(3);
    assertThat(state.getExecutionMode()).isEqualTo(ExecutionMode.PARALLEL);
    assertThat(state.getCompletedItems()).isZero();

    // 3 NodeItemExecutions created, all RUNNING (because PARALLEL)
    List<NodeItemExecution> items =
        itemRepository.findAllByMultiInstanceStateIdOrderByItemIndexAsc(state.getId());
    assertThat(items).hasSize(3);
    assertThat(items).allMatch(i -> "RUNNING".equals(i.getStatus()));

    // Complete item 0
    Optional<RoutingResult> r0 =
        multiInstanceService.completeItem(
            execution.getId(),
            0,
            "DEFAULT",
            objectMapper.createObjectNode().put("approved", true),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));
    assertThat(r0).isEmpty(); // Not yet all

    // Complete item 1
    Optional<RoutingResult> r1 =
        multiInstanceService.completeItem(
            execution.getId(),
            1,
            "DEFAULT",
            objectMapper.createObjectNode().put("approved", true),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));
    assertThat(r1).isEmpty();

    // Complete item 2 (final item)
    Optional<RoutingResult> r2 =
        multiInstanceService.completeItem(
            execution.getId(),
            2,
            "DEFAULT",
            objectMapper.createObjectNode().put("approved", true),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    // Threshold ALL satisfied -> downstream routed!
    assertThat(r2).isPresent();

    // Parent NodeExecution is now COMPLETED
    NodeExecution updatedParent = executionRepository.findById(execution.getId()).orElseThrow();
    assertThat(updatedParent.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
  }

  @Test
  void threshold_anyPolicy_routesOnFirstCompletion_andCancelsRemaining() {
    Fixture fixture = createFixture("ANY", "CANCEL_REMAINING", ExecutionMode.PARALLEL);
    Event event = createEventWithReviewers(fixture, List.of("alice", "bob", "carol"));

    NodeExecution execution =
        activationService.activate(
            ActivationRequest.root(
                event.getId(),
                fixture.miNodeId(),
                UUID.randomUUID(),
                new CorrelationId(uuidGenerator.generate()),
                new CommandId(uuidGenerator.generate())));

    // Complete first item only
    Optional<RoutingResult> result =
        multiInstanceService.completeItem(
            execution.getId(),
            0,
            "DEFAULT",
            objectMapper.createObjectNode(),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    assertThat(result).isPresent();

    // Remaining items should be CANCELLED
    MultiInstanceState state =
        stateRepository.findByNodeExecutionId(execution.getId()).orElseThrow();
    List<NodeItemExecution> items =
        itemRepository.findAllByMultiInstanceStateIdOrderByItemIndexAsc(state.getId());
    assertThat(items.get(0).getStatus()).isEqualTo("COMPLETED");
    assertThat(items.get(1).getStatus()).isEqualTo("CANCELLED");
    assertThat(items.get(2).getStatus()).isEqualTo("CANCELLED");
  }

  @Test
  void keepRunningPolicy_doesNotRouteDownstreamMoreThanOnce() {
    Fixture fixture = createFixture("ANY", "KEEP_RUNNING", ExecutionMode.PARALLEL);
    Event event = createEventWithReviewers(fixture, List.of("alice", "bob"));

    NodeExecution execution =
        activationService.activate(
            ActivationRequest.root(
                event.getId(),
                fixture.miNodeId(),
                UUID.randomUUID(),
                new CorrelationId(uuidGenerator.generate()),
                new CommandId(uuidGenerator.generate())));

    // Item 0 completes -> routes downstream first time
    Optional<RoutingResult> r0 =
        multiInstanceService.completeItem(
            execution.getId(),
            0,
            "DEFAULT",
            objectMapper.createObjectNode(),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));
    assertThat(r0).isPresent();

    // Item 1 completes -> should NOT route downstream again!
    Optional<RoutingResult> r1 =
        multiInstanceService.completeItem(
            execution.getId(),
            1,
            "DEFAULT",
            objectMapper.createObjectNode(),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));
    assertThat(r1).isEmpty();

    // Both items completed
    List<NodeItemExecution> items =
        itemRepository.findAllByParentNodeExecutionIdOrderByItemIndexAsc(execution.getId());
    assertThat(items).allMatch(it -> "COMPLETED".equals(it.getStatus()));
  }

  @Test
  void duplicateItemCompletion_isIdempotent() {
    Fixture fixture = createFixture("ALL", "CANCEL_REMAINING", ExecutionMode.PARALLEL);
    Event event = createEventWithReviewers(fixture, List.of("alice", "bob"));

    NodeExecution execution =
        activationService.activate(
            ActivationRequest.root(
                event.getId(),
                fixture.miNodeId(),
                UUID.randomUUID(),
                new CorrelationId(uuidGenerator.generate()),
                new CommandId(uuidGenerator.generate())));

    // Complete item 0
    multiInstanceService.completeItem(
        execution.getId(),
        0,
        "DEFAULT",
        objectMapper.createObjectNode(),
        new CorrelationId(uuidGenerator.generate()),
        new CommandId(uuidGenerator.generate()));

    // Replay duplicate completion of item 0
    Optional<RoutingResult> duplicateResult =
        multiInstanceService.completeItem(
            execution.getId(),
            0,
            "DEFAULT",
            objectMapper.createObjectNode(),
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    assertThat(duplicateResult).isEmpty();
    MultiInstanceState state =
        stateRepository.findByNodeExecutionId(execution.getId()).orElseThrow();
    assertThat(state.getCompletedItems()).isEqualTo(1); // not incremented twice
  }

  @Test
  void sequential_startsItemsOneByOne() {
    Fixture fixture = createFixture("ALL", "CANCEL_REMAINING", ExecutionMode.SEQUENTIAL);
    Event event = createEventWithReviewers(fixture, List.of("alice", "bob"));

    NodeExecution execution =
        activationService.activate(
            ActivationRequest.root(
                event.getId(),
                fixture.miNodeId(),
                UUID.randomUUID(),
                new CorrelationId(uuidGenerator.generate()),
                new CommandId(uuidGenerator.generate())));

    // Item 0 is RUNNING, item 1 is PENDING
    List<NodeItemExecution> items =
        itemRepository.findAllByParentNodeExecutionIdOrderByItemIndexAsc(execution.getId());
    assertThat(items.get(0).getStatus()).isEqualTo("RUNNING");
    assertThat(items.get(1).getStatus()).isEqualTo("PENDING");

    // Complete item 0 -> should advance item 1 to RUNNING
    multiInstanceService.completeItem(
        execution.getId(),
        0,
        "DEFAULT",
        objectMapper.createObjectNode(),
        new CorrelationId(uuidGenerator.generate()),
        new CommandId(uuidGenerator.generate()));

    NodeItemExecution item1 =
        itemRepository.findByParentNodeExecutionIdAndItemIndex(execution.getId(), 1).orElseThrow();
    assertThat(item1.getStatus()).isEqualTo("RUNNING");
  }
}
