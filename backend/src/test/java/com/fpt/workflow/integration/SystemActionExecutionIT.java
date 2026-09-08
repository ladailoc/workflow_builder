package com.fpt.workflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.connector.service.ConnectorManagementService;
import com.fpt.workflow.integration.client.DefaultConnectorActionClient;
import com.fpt.workflow.integration.client.IntegrationCallResponse;
import com.fpt.workflow.integration.domain.AttemptStatus;
import com.fpt.workflow.integration.domain.IntegrationAttempt;
import com.fpt.workflow.integration.domain.IntegrationErrorCategory;
import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationAttemptRepository;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.integration.service.SystemActionExecutionService;
import com.fpt.workflow.integration.service.SystemActionResult;
import com.fpt.workflow.integration.service.SystemActionTransactionService;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
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

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class SystemActionExecutionIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  private static final UUID TECH_ADMIN_ID = UUID.fromString("10000000-0000-4000-8000-000000000099");
  private static final ActorContext TECH_ADMIN =
      new ActorContext(TECH_ADMIN_ID, "techAdmin", Set.of(RoleKey.ADMIN), Set.of());
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-03-31T09:00:00Z");

  @Autowired private SystemActionExecutionService systemActionExecutionService;
  @Autowired private ConnectorManagementService managementService;
  @Autowired private DefaultConnectorActionClient actionClient;
  @Autowired private IntegrationExecutionRepository executionRepository;
  @Autowired private IntegrationAttemptRepository attemptRepository;
  @Autowired private SystemActionTransactionService transactionService;
  @Autowired private EventRepository eventRepository;
  @Autowired private NodeActivationService activationService;
  @Autowired private RoutingService routingService;
  @Autowired private UuidGenerator uuidGenerator;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    actionClient.clearTestDelegate();
  }

  @Test
  void testSuccess_executesTx1ExternalCallTx2AndRoutesDownstream() {
    Fixture f = setupFixture("ORDER_API", true, 2, 0, List.of("SERVICE_UNAVAILABLE_503"));

    actionClient.setTestDelegate(
        req -> {
          ObjectNode response = objectMapper.createObjectNode();
          response.put("orderId", "ORD-999");
          response.put("status", "CONFIRMED");
          response.put("secretKey", "super-secret-must-mask");
          return IntegrationCallResponse.success(200, response);
        });

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    SystemActionResult result =
        systemActionExecutionService.execute(f.systemActionExecution().getId(), corr, cmd);

    // 1. Result verification
    assertThat(result.outcomePort()).isEqualTo("SUCCESS");
    assertThat(result.nodeExecution().getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(result.nodeExecution().getOutcomePort()).isEqualTo("SUCCESS");

    // 2. IntegrationExecution verification
    IntegrationExecution execution =
        executionRepository.findByNodeExecutionId(f.systemActionExecution().getId()).orElseThrow();
    assertThat(execution.getStatus()).isEqualTo(IntegrationExecutionStatus.COMPLETED);
    assertThat(execution.getConnectorKey()).isEqualTo(f.connectorKey());
    assertThat(execution.getActionKey()).isEqualTo("ORDER_API");
    assertThat(execution.getActionVersion()).isEqualTo(1);
    assertThat(execution.getErrorCategory()).isEqualTo(IntegrationErrorCategory.NONE);
    assertThat(execution.getIdempotencyKey()).isNotBlank();
    assertThat(execution.getLogicalActionIdentity()).contains(f.connectorKey() + "/ORDER_API:v1");
    // Secret masking verification
    assertThat(execution.getSanitizedResponseJson()).contains("***REDACTED***");
    assertThat(execution.getSanitizedResponseJson()).doesNotContain("super-secret-must-mask");

    // 3. IntegrationAttempts verification: exactly 1 attempt
    List<IntegrationAttempt> attempts =
        attemptRepository.findAllByIntegrationExecutionIdOrderByAttemptNumberAsc(execution.getId());
    assertThat(attempts).hasSize(1);
    IntegrationAttempt attempt1 = attempts.get(0);
    assertThat(attempt1.getAttemptNumber()).isEqualTo(1);
    assertThat(attempt1.getStatus()).isEqualTo(AttemptStatus.SUCCESS);
    assertThat(attempt1.getErrorCategory()).isEqualTo(IntegrationErrorCategory.NONE);

    // 4. Routing verification: routed downstream via SUCCESS port to successEndNode
    assertThat(result.routingResult().activations()).hasSize(1);
    NodeExecution activatedLeaf = result.routingResult().activations().get(0);
    assertThat(activatedLeaf.getNodeDefinitionId()).isEqualTo(f.successEndNodeId());
  }

  @Test
  void test503_retriesAndReusesSameLogicalIdempotencyIdentity() {
    Fixture f = setupFixture("PAYMENT_CHARGE", true, 2, 10, List.of("SERVICE_UNAVAILABLE_503"));

    AtomicInteger calls = new AtomicInteger(0);
    List<String> capturedIdempotencyKeys = new CopyOnWriteArrayList<>();

    actionClient.setTestDelegate(
        req -> {
          capturedIdempotencyKeys.add(req.idempotencyKey());
          if (calls.incrementAndGet() == 1) {
            return IntegrationCallResponse.serviceUnavailable("Gateway temporary 503");
          }
          ObjectNode response = objectMapper.createObjectNode();
          response.put("chargeId", "CH-777");
          return IntegrationCallResponse.success(200, response);
        });

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    SystemActionResult result =
        systemActionExecutionService.execute(f.systemActionExecution().getId(), corr, cmd);

    assertThat(result.outcomePort()).isEqualTo("SUCCESS");
    assertThat(calls.get()).isEqualTo(2);

    // Reuse same logical idempotency identity across retries
    assertThat(capturedIdempotencyKeys).hasSize(2);
    assertThat(capturedIdempotencyKeys.get(0)).isEqualTo(capturedIdempotencyKeys.get(1));

    IntegrationExecution execution =
        executionRepository.findByNodeExecutionId(f.systemActionExecution().getId()).orElseThrow();
    assertThat(execution.getStatus()).isEqualTo(IntegrationExecutionStatus.COMPLETED);

    List<IntegrationAttempt> attempts =
        attemptRepository.findAllByIntegrationExecutionIdOrderByAttemptNumberAsc(execution.getId());
    assertThat(attempts).hasSize(2);

    IntegrationAttempt attempt1 = attempts.get(0);
    assertThat(attempt1.getAttemptNumber()).isEqualTo(1);
    assertThat(attempt1.getStatus()).isEqualTo(AttemptStatus.FAILURE);
    assertThat(attempt1.getErrorCategory())
        .isEqualTo(IntegrationErrorCategory.SERVICE_UNAVAILABLE_503);
    assertThat(attempt1.getErrorMessage()).contains("Gateway temporary 503");

    IntegrationAttempt attempt2 = attempts.get(1);
    assertThat(attempt2.getAttemptNumber()).isEqualTo(2);
    assertThat(attempt2.getStatus()).isEqualTo(AttemptStatus.SUCCESS);
    assertThat(attempt2.getErrorCategory()).isEqualTo(IntegrationErrorCategory.NONE);
  }

  @Test
  void testTimeout_retriesAndCompletes() {
    Fixture f = setupFixture("INVENTORY_CHECK", true, 2, 10, List.of("TIMEOUT"));

    AtomicInteger calls = new AtomicInteger(0);
    actionClient.setTestDelegate(
        req -> {
          if (calls.incrementAndGet() == 1) {
            throw new RuntimeException(new SocketTimeoutException("Read timed out after 3000ms"));
          }
          ObjectNode response = objectMapper.createObjectNode();
          response.put("inStock", true);
          return IntegrationCallResponse.success(200, response);
        });

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    SystemActionResult result =
        systemActionExecutionService.execute(f.systemActionExecution().getId(), corr, cmd);

    assertThat(result.outcomePort()).isEqualTo("SUCCESS");
    assertThat(calls.get()).isEqualTo(2);

    IntegrationExecution execution =
        executionRepository.findByNodeExecutionId(f.systemActionExecution().getId()).orElseThrow();
    List<IntegrationAttempt> attempts =
        attemptRepository.findAllByIntegrationExecutionIdOrderByAttemptNumberAsc(execution.getId());
    assertThat(attempts).hasSize(2);
    assertThat(attempts.get(0).getStatus()).isEqualTo(AttemptStatus.FAILURE);
    assertThat(attempts.get(0).getErrorCategory()).isEqualTo(IntegrationErrorCategory.TIMEOUT);
    assertThat(attempts.get(1).getStatus()).isEqualTo(AttemptStatus.SUCCESS);
  }

  @Test
  void testLostResponse_retriesWithSameIdempotencyIdentity() {
    Fixture f = setupFixture("NOTIFY_USER", true, 1, 10, List.of("LOST_RESPONSE"));

    AtomicInteger calls = new AtomicInteger(0);
    List<String> capturedKeys = new CopyOnWriteArrayList<>();

    actionClient.setTestDelegate(
        req -> {
          capturedKeys.add(req.idempotencyKey());
          if (calls.incrementAndGet() == 1) {
            return IntegrationCallResponse.lostResponse("Connection reset before response read");
          }
          ObjectNode response = objectMapper.createObjectNode();
          response.put("notified", true);
          return IntegrationCallResponse.success(200, response);
        });

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    SystemActionResult result =
        systemActionExecutionService.execute(f.systemActionExecution().getId(), corr, cmd);

    assertThat(result.outcomePort()).isEqualTo("SUCCESS");
    assertThat(calls.get()).isEqualTo(2);
    assertThat(capturedKeys.get(0)).isEqualTo(capturedKeys.get(1));

    IntegrationExecution execution =
        executionRepository.findByNodeExecutionId(f.systemActionExecution().getId()).orElseThrow();
    List<IntegrationAttempt> attempts =
        attemptRepository.findAllByIntegrationExecutionIdOrderByAttemptNumberAsc(execution.getId());
    assertThat(attempts).hasSize(2);
    assertThat(attempts.get(0).getErrorCategory())
        .isEqualTo(IntegrationErrorCategory.LOST_RESPONSE);
    assertThat(attempts.get(1).getStatus()).isEqualTo(AttemptStatus.SUCCESS);
  }

  @Test
  void testRetryExhaustion_exhaustsRetriesAndRoutesViaErrorPort() {
    Fixture f = setupFixture("EXTERNAL_SYNC", true, 2, 10, List.of("SERVICE_UNAVAILABLE_503"));

    AtomicInteger calls = new AtomicInteger(0);
    actionClient.setTestDelegate(
        req -> {
          calls.incrementAndGet();
          return IntegrationCallResponse.serviceUnavailable("Downstream persistent outage");
        });

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    SystemActionResult result =
        systemActionExecutionService.execute(f.systemActionExecution().getId(), corr, cmd);

    // 1. Result mapped to ERROR outcome port
    assertThat(result.outcomePort()).isEqualTo("ERROR");
    assertThat(calls.get()).isEqualTo(3); // 1 initial attempt + 2 retries = 3

    // 2. IntegrationExecution is marked FAILED with error category
    IntegrationExecution execution =
        executionRepository.findByNodeExecutionId(f.systemActionExecution().getId()).orElseThrow();
    assertThat(execution.getStatus()).isEqualTo(IntegrationExecutionStatus.FAILED);
    assertThat(execution.getErrorCategory())
        .isEqualTo(IntegrationErrorCategory.SERVICE_UNAVAILABLE_503);

    // 3. IntegrationAttempts has 3 failed attempts
    List<IntegrationAttempt> attempts =
        attemptRepository.findAllByIntegrationExecutionIdOrderByAttemptNumberAsc(execution.getId());
    assertThat(attempts).hasSize(3);
    assertThat(attempts).allMatch(a -> a.getStatus() == AttemptStatus.FAILURE);
    assertThat(attempts)
        .allMatch(a -> a.getErrorCategory() == IntegrationErrorCategory.SERVICE_UNAVAILABLE_503);

    // 4. Downstream routing followed ERROR port to errorEndNode
    assertThat(result.routingResult().activations()).hasSize(1);
    NodeExecution errorNode = result.routingResult().activations().get(0);
    assertThat(errorNode.getNodeDefinitionId()).isEqualTo(f.errorEndNodeId());
  }

  @Test
  void testNonIdempotentUncertainOutcome_requiresManualReconciliationWithoutRetry() {
    Fixture f =
        setupFixture(
            "NON_IDEMPOTENT_POST",
            false,
            3,
            10,
            List.of("LOST_RESPONSE", "SERVICE_UNAVAILABLE_503"));

    AtomicInteger calls = new AtomicInteger(0);
    actionClient.setTestDelegate(
        req -> {
          calls.incrementAndGet();
          return IntegrationCallResponse.lostResponse("Lost response on non-idempotent action");
        });

    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());

    var result =
        systemActionExecutionService.executeDurableAttempt(
            f.systemActionExecution().getId(), 1, corr, cmd);

    // Non-idempotent action must NOT perform blind automatic retry: exactly 1 call!
    assertThat(calls.get()).isEqualTo(1);
    assertThat(result.terminal()).isTrue();
    assertThat(result.terminalResult().outcomePort()).isEqualTo("MANUAL_RECONCILIATION");
    assertThat(result.terminalResult().routingResult()).isNull();

    IntegrationExecution execution =
        executionRepository.findByNodeExecutionId(f.systemActionExecution().getId()).orElseThrow();
    assertThat(execution.getStatus()).isEqualTo(IntegrationExecutionStatus.MANUAL_RECONCILIATION);
    assertThat(execution.getErrorCategory()).isEqualTo(IntegrationErrorCategory.LOST_RESPONSE);
    assertThat(result.terminalResult().nodeExecution().getStatus())
        .isEqualTo(NodeExecutionStatus.WAITING);
    assertThat(result.terminalResult().nodeExecution().getWaitReason())
        .isEqualTo(RuntimeWaitReason.MANUAL_RECONCILIATION);

    List<IntegrationAttempt> attempts =
        attemptRepository.findAllByIntegrationExecutionIdOrderByAttemptNumberAsc(execution.getId());
    assertThat(attempts).hasSize(1);
    assertThat(attempts.get(0).getStatus()).isEqualTo(AttemptStatus.FAILURE);

    assertThat(eventRepository.findById(f.event().getId()).orElseThrow().getWaitReason())
        .isEqualTo(RuntimeWaitReason.MANUAL_RECONCILIATION);
  }

  @Test
  void reclaimedNonIdempotentRunningAttempt_neverCallsExternalActionAgain() {
    Fixture f = setupFixture("NON_IDEMPOTENT_RECOVERY", false, 3, 10, List.of("TIMEOUT"));
    transactionService.startOrResumeExecutionTx(
        f.event().getId(),
        f.systemActionExecution().getId(),
        f.connectorKey(),
        "NON_IDEMPOTENT_RECOVERY",
        1,
        f.connectorActionVersionId(),
        "logical:" + f.systemActionExecution().getId(),
        "idemp:" + f.systemActionExecution().getId(),
        f.systemActionExecution().getInputJson(),
        1,
        new CorrelationId(uuidGenerator.generate()),
        new CommandId(uuidGenerator.generate()));

    AtomicInteger calls = new AtomicInteger();
    actionClient.setTestDelegate(
        request -> {
          calls.incrementAndGet();
          return IntegrationCallResponse.success(200, objectMapper.createObjectNode());
        });

    var result =
        systemActionExecutionService.executeDurableAttempt(
            f.systemActionExecution().getId(),
            2,
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    assertThat(calls).hasValue(0);
    assertThat(result.terminal()).isTrue();
    assertThat(result.terminalResult().outcomePort()).isEqualTo("MANUAL_RECONCILIATION");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Fixture setup helper
  // ────────────────────────────────────────────────────────────────────────────

  private record Fixture(
      Event event,
      NodeExecution systemActionExecution,
      String connectorKey,
      UUID connectorActionVersionId,
      UUID successEndNodeId,
      UUID errorEndNodeId) {}

  private Fixture setupFixture(
      String actionKey,
      boolean idempotent,
      int maxRetries,
      long backoffMs,
      List<String> retryableErrors) {

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    String connectorKey = "CONN_" + suffix;
    UUID defId = UUID.randomUUID();
    UUID verId = UUID.randomUUID();
    UUID startId = UUID.randomUUID();
    UUID sysActionId = UUID.randomUUID();
    UUID successEndId = UUID.randomUUID();
    UUID errorEndId = UUID.randomUUID();
    UUID edgeStartToSys = UUID.randomUUID();
    UUID edgeSysToSuccess = UUID.randomUUID();
    UUID edgeSysToError = UUID.randomUUID();
    UUID reqTypeId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID revId = UUID.randomUUID();

    // 1. Connector Definition, Action, Version via ConnectorManagementService
    managementService.registerConnector(
        connectorKey,
        "Test Connector " + suffix,
        "REST",
        "testHandler",
        objectMapper.createObjectNode(),
        "vault://test/cred",
        TECH_ADMIN);

    managementService.registerAction(connectorKey, actionKey, "Test Action", TECH_ADMIN);

    ObjectNode retryPolicy = objectMapper.createObjectNode();
    retryPolicy.put("idempotent", idempotent);
    retryPolicy.put("maxRetries", maxRetries);
    retryPolicy.put("backoffMs", backoffMs);
    ArrayNode errArray = retryPolicy.putArray("retryableErrors");
    retryableErrors.forEach(errArray::add);

    var connectorActionVersion =
        managementService.publishActionVersion(
            connectorKey,
            actionKey,
            1,
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            retryPolicy,
            retryPolicy,
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            TECH_ADMIN);

    // 2. Workflow Definition + Version
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'System Action Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        "wf-sys-" + suffix,
        ACTOR_ID,
        ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        verId,
        defId,
        ACTOR_ID);

    // 3. Nodes: START -> SYSTEM_ACTION -> END(SUCCESS) & END(ERROR)
    ObjectNode startConfig = objectMapper.createObjectNode();
    startConfig.put("routingMode", "ALL_OUTGOING");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start', 1, ?::jsonb, '{}'::jsonb)",
        startId,
        verId,
        startConfig.toString());

    ObjectNode sysConfig = objectMapper.createObjectNode();
    sysConfig.put("connectorKey", connectorKey);
    sysConfig.put("actionKey", actionKey);
    sysConfig.put("actionVersion", 1);
    sysConfig.put("credentialRef", "vault://test/cred");
    sysConfig.put("routingMode", "SINGLE_BY_PORT");

    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'sys_action', 'SYSTEM_ACTION', 'System Action Node', 1, ?::jsonb, '{}'::jsonb)",
        sysActionId,
        verId,
        sysConfig.toString());

    ObjectNode successEndConfig = objectMapper.createObjectNode();
    successEndConfig.put("outcome", "SUCCESS_REACHED");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'success_end', 'END', 'Success End', 1, ?::jsonb, '{}'::jsonb)",
        successEndId,
        verId,
        successEndConfig.toString());

    ObjectNode errorEndConfig = objectMapper.createObjectNode();
    errorEndConfig.put("outcome", "ERROR_REACHED");
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'error_end', 'END', 'Error End', 1, ?::jsonb, '{}'::jsonb)",
        errorEndId,
        verId,
        errorEndConfig.toString());

    // 4. Edges
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'STARTED', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeStartToSys,
        verId,
        startId,
        sysActionId);

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'SUCCESS', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeSysToSuccess,
        verId,
        sysActionId,
        successEndId);

    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'ERROR', ?, 0, true, 'CONDITIONAL', '{}'::jsonb)",
        edgeSysToError,
        verId,
        sysActionId,
        errorEndId);

    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() WHERE id = ?",
        ACTOR_ID,
        verId);

    jdbcTemplate.update(
        "UPDATE workflow_definitions SET current_published_version_id = ? WHERE id = ?",
        verId,
        defId);

    // 5. Request Type, Ticket, Revision
    jdbcTemplate.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Req', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        reqTypeId,
        "req-" + suffix,
        defId);

    jdbcTemplate.update(
        "INSERT INTO tickets (id, request_type_id, creator_id, status, data_json, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, now(), now())",
        ticketId,
        reqTypeId,
        ACTOR_ID);

    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id, ticket_id, revision_no, data_snapshot_json, source_schema_version, schema_checksum, submitted_by, submitted_at) "
            + "VALUES (?, ?, 1, '{}'::jsonb, 'v1', 'checksum', ?, now())",
        revId,
        ticketId,
        ACTOR_ID);

    jdbcTemplate.update(
        "UPDATE tickets SET status = 'SUBMITTED', data_revision = 1, current_revision_id = ?, submitted_at = now(), updated_at = now() WHERE id = ?",
        revId,
        ticketId);

    // 6. Event
    Event ev =
        Event.createRoot(
            uuidGenerator.generate(),
            ticketId,
            verId,
            revId,
            null,
            null,
            "USER_SUBMIT",
            "corr-" + suffix,
            objectMapper.createObjectNode(),
            ACTOR_ID,
            NOW);
    ev.markRunning();
    ev = eventRepository.save(ev);

    // 7. Activate START node and route to SYSTEM_ACTION
    CorrelationId corr = new CorrelationId(uuidGenerator.generate());
    CommandId cmd = new CommandId(uuidGenerator.generate());
    NodeExecution startExecution =
        activationService.activate(
            ActivationRequest.root(ev.getId(), startId, UUID.randomUUID(), corr, cmd));

    RoutingResult startRoute = routingService.route(startExecution.getId(), corr, cmd);
    NodeExecution sysActionExecution = startRoute.activations().get(0);

    return new Fixture(
        ev,
        sysActionExecution,
        connectorKey,
        connectorActionVersion.getId(),
        successEndId,
        errorEndId);
  }
}
