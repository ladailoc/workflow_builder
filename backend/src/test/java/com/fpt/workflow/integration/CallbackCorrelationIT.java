package com.fpt.workflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.connector.service.ConnectorManagementService;
import com.fpt.workflow.integration.domain.IntegrationCallback;
import com.fpt.workflow.integration.domain.IntegrationCallbackStatus;
import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationCallbackRepository;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.integration.service.CallbackCommand;
import com.fpt.workflow.integration.service.CallbackCorrelationService;
import com.fpt.workflow.integration.service.CallbackProcessingResult;
import com.fpt.workflow.integration.service.CallbackSignatureValidator;
import com.fpt.workflow.integration.service.ConnectorCredentialProvider;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingResult;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.runtime.routing.repository.RoutingDecisionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class CallbackCorrelationIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  private static final UUID TECH_ADMIN_ID = UUID.fromString("10000000-0000-4000-8000-000000000099");
  private static final ActorContext TECH_ADMIN =
      new ActorContext(TECH_ADMIN_ID, "techAdmin", Set.of(RoleKey.ADMIN), Set.of());
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UuidGenerator uuidGenerator;
  @Autowired private ConnectorManagementService managementService;
  @Autowired private ConnectorCredentialProvider credentialProvider;
  @Autowired private CallbackSignatureValidator signatureValidator;
  @Autowired private CallbackCorrelationService callbackCorrelationService;
  @Autowired private IntegrationExecutionRepository executionRepository;
  @Autowired private IntegrationCallbackRepository callbackRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private RoutingDecisionRepository routingDecisionRepository;
  @Autowired private NodeActivationService activationService;
  @Autowired private RoutingService routingService;

  private final String signingSecret = "test-secret-key-xyz-789";

  @BeforeEach
  void setUp() {
    credentialProvider.registerSecret("vault://test/cred", signingSecret);
  }

  @Test
  void testValidCallback_completesExecutionAndActivatesDownstream() {
    Fixture f = setupWaitingFixture("VALID_CALLBACK");
    String corrId = f.callbackCorrelationId();

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("result", "PAYMENT_CONFIRMED");
    payload.put("amount", 250000);
    payload.put("secretPassword", "raw-secret-should-be-masked");
    String rawPayload = payload.toString();
    Instant timestamp = Instant.now();
    String sig =
        signatureValidator.computeSignature(signingSecret, timestamp.toString(), rawPayload);

    CallbackCommand cmd =
        new CallbackCommand(
            corrId,
            f.connectorKey(),
            "evt-" + UUID.randomUUID(),
            sig,
            timestamp,
            rawPayload,
            payload,
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    CallbackProcessingResult result = callbackCorrelationService.processCallback(cmd);

    // 1. Result ACCEPTED
    assertThat(result.status()).isEqualTo(IntegrationCallbackStatus.ACCEPTED);
    assertThat(result.outcomePort()).isEqualTo("SUCCESS");

    // 2. IntegrationExecution is COMPLETED
    IntegrationExecution execution = executionRepository.findById(f.executionId()).orElseThrow();
    assertThat(execution.getStatus()).isEqualTo(IntegrationExecutionStatus.COMPLETED);
    assertThat(execution.getCompletedAt()).isNotNull();

    // 3. NodeExecution is COMPLETED with SUCCESS
    NodeExecution node = nodeExecutionRepository.findById(f.nodeExecutionId()).orElseThrow();
    assertThat(node.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(node.getOutcomePort()).isEqualTo("SUCCESS");

    // 4. Downstream node activated
    List<NodeExecution> downstream =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(f.event().getId());
    assertThat(downstream).anyMatch(n -> n.getNodeDefinitionId().equals(f.successEndNodeId()));

    // 5. IntegrationCallback audited with sanitized payload
    IntegrationCallback callback =
        callbackRepository.findByCallbackCorrelationId(corrId).orElseThrow();
    assertThat(callback.getStatus()).isEqualTo(IntegrationCallbackStatus.ACCEPTED);
    assertThat(callback.isSignatureValid()).isTrue();
    assertThat(callback.getSanitizedPayloadJson()).contains("***REDACTED***");
    assertThat(callback.getSanitizedPayloadJson()).doesNotContain("raw-secret-should-be-masked");
  }

  @Test
  void testDuplicateCallback_idempotentlyReturnsOriginalResult() {
    Fixture f = setupWaitingFixture("DUPLICATE_CALLBACK");
    String corrId = f.callbackCorrelationId();
    String externalEventId = "evt-dedup-123";

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("status", "SUCCESS");
    String rawPayload = payload.toString();
    Instant timestamp = Instant.now();
    String sig =
        signatureValidator.computeSignature(signingSecret, timestamp.toString(), rawPayload);

    CallbackCommand cmd =
        new CallbackCommand(
            corrId,
            f.connectorKey(),
            externalEventId,
            sig,
            timestamp,
            rawPayload,
            payload,
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    // First delivery -> ACCEPTED
    CallbackProcessingResult first = callbackCorrelationService.processCallback(cmd);
    assertThat(first.status()).isEqualTo(IntegrationCallbackStatus.ACCEPTED);

    int activationsAfterFirst =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(f.event().getId()).size();

    // Second delivery (duplicate externalEventId) -> DUPLICATE
    CallbackProcessingResult second = callbackCorrelationService.processCallback(cmd);
    assertThat(second.status()).isEqualTo(IntegrationCallbackStatus.DUPLICATE);
    assertThat(second.callbackId()).isEqualTo(first.callbackId());

    // Verify downstream was NOT activated again
    int activationsAfterSecond =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(f.event().getId()).size();
    assertThat(activationsAfterSecond).isEqualTo(activationsAfterFirst);
  }

  @Test
  void testInvalidSignature_rejectsWithoutStateChange() {
    Fixture f = setupWaitingFixture("BAD_SIG");
    String corrId = f.callbackCorrelationId();

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("confirmed", true);
    Instant timestamp = Instant.now();

    CallbackCommand cmd =
        new CallbackCommand(
            corrId,
            f.connectorKey(),
            "evt-" + UUID.randomUUID(),
            "sha256=invalid-signature-hash-value-123",
            timestamp,
            payload.toString(),
            payload,
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    CallbackProcessingResult result = callbackCorrelationService.processCallback(cmd);

    // Rejected
    assertThat(result.status()).isEqualTo(IntegrationCallbackStatus.REJECTED);
    assertThat(result.message()).contains("Invalid callback signature");

    // Execution & Node state remain unchanged
    IntegrationExecution execution = executionRepository.findById(f.executionId()).orElseThrow();
    assertThat(execution.getStatus()).isEqualTo(IntegrationExecutionStatus.WAITING_CALLBACK);

    NodeExecution node = nodeExecutionRepository.findById(f.nodeExecutionId()).orElseThrow();
    assertThat(node.getStatus()).isEqualTo(NodeExecutionStatus.WAITING);
  }

  @Test
  void testReplay_rejectsExpiredOrReplayedTimestamp() {
    Fixture f = setupWaitingFixture("EXPIRED_TIME");
    String corrId = f.callbackCorrelationId();

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("status", "OK");
    String rawPayload = payload.toString();
    // 10 minutes in the past -> exceeds 300s window
    Instant expiredTimestamp = Instant.now().minus(Duration.ofMinutes(10));
    String sig =
        signatureValidator.computeSignature(signingSecret, expiredTimestamp.toString(), rawPayload);

    CallbackCommand cmd =
        new CallbackCommand(
            corrId,
            f.connectorKey(),
            "evt-" + UUID.randomUUID(),
            sig,
            expiredTimestamp,
            rawPayload,
            payload,
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    CallbackProcessingResult result = callbackCorrelationService.processCallback(cmd);

    // Rejected due to timestamp drift
    assertThat(result.status()).isEqualTo(IntegrationCallbackStatus.REJECTED);
    assertThat(result.message()).contains("allowable tolerance window");

    // Execution state untouched
    IntegrationExecution execution = executionRepository.findById(f.executionId()).orElseThrow();
    assertThat(execution.getStatus()).isEqualTo(IntegrationExecutionStatus.WAITING_CALLBACK);
  }

  @Test
  void testWrongCorrelation_rejectsUnknownCorrelationId() {
    Fixture f = setupWaitingFixture("WRONG_CORR");
    String wrongCorrId = "cbk_non_existent_correlation_token_999";

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("key", "val");
    String rawPayload = payload.toString();
    Instant timestamp = Instant.now();
    String sig =
        signatureValidator.computeSignature(signingSecret, timestamp.toString(), rawPayload);

    CallbackCommand cmd =
        new CallbackCommand(
            wrongCorrId,
            f.connectorKey(),
            "evt-" + UUID.randomUUID(),
            sig,
            timestamp,
            rawPayload,
            payload,
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    CallbackProcessingResult result = callbackCorrelationService.processCallback(cmd);

    assertThat(result.status()).isEqualTo(IntegrationCallbackStatus.REJECTED);
    assertThat(result.message()).contains("Wrong or unknown callbackCorrelationId");

    // Callback is audited as REJECTED
    IntegrationCallback cb =
        callbackRepository.findByCallbackCorrelationId(wrongCorrId).orElseThrow();
    assertThat(cb.getStatus()).isEqualTo(IntegrationCallbackStatus.REJECTED);
  }

  @Test
  void testLateCallback_persistsLateAndTerminalWinsWithoutResuming() {
    Fixture f = setupWaitingFixture("LATE_CALLBACK");
    String corrId = f.callbackCorrelationId();

    // Transition node to terminal CANCELLED before callback arrives
    NodeExecution node = nodeExecutionRepository.findById(f.nodeExecutionId()).orElseThrow();
    node.cancel(Instant.now());
    nodeExecutionRepository.saveAndFlush(node);

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("status", "SUCCESS");
    payload.put("message", "Processed by provider");
    String rawPayload = payload.toString();
    Instant timestamp = Instant.now();
    String sig =
        signatureValidator.computeSignature(signingSecret, timestamp.toString(), rawPayload);

    CallbackCommand cmd =
        new CallbackCommand(
            corrId,
            f.connectorKey(),
            "evt-" + UUID.randomUUID(),
            sig,
            timestamp,
            rawPayload,
            payload,
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    CallbackProcessingResult result = callbackCorrelationService.processCallback(cmd);

    // 1. Result is LATE (Terminal-Wins)
    assertThat(result.status()).isEqualTo(IntegrationCallbackStatus.LATE);
    assertThat(result.message()).contains("terminal-wins");

    // 2. NodeExecution remains CANCELLED (never revived)
    NodeExecution reloadedNode =
        nodeExecutionRepository.findById(f.nodeExecutionId()).orElseThrow();
    assertThat(reloadedNode.getStatus()).isEqualTo(NodeExecutionStatus.CANCELLED);

    // 3. No downstream activation (only start and sys_action exist; neither successEnd nor errorEnd
    // activated)
    List<NodeExecution> executions =
        nodeExecutionRepository.findAllByEventIdOrderByCreatedAtAsc(f.event().getId());
    assertThat(executions).noneMatch(n -> n.getNodeDefinitionId().equals(f.successEndNodeId()));
    assertThat(executions).noneMatch(n -> n.getNodeDefinitionId().equals(f.errorEndNodeId()));

    // 4. Late callback is audited
    IntegrationCallback cb = callbackRepository.findByCallbackCorrelationId(corrId).orElseThrow();
    assertThat(cb.getStatus()).isEqualTo(IntegrationCallbackStatus.LATE);
  }

  @Test
  void testCancelVsCallbackRace_terminalWins() throws InterruptedException {
    Fixture f = setupWaitingFixture("RACE_TEST");
    String corrId = f.callbackCorrelationId();

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("status", "SUCCESS");
    String rawPayload = payload.toString();
    Instant timestamp = Instant.now();
    String sig =
        signatureValidator.computeSignature(signingSecret, timestamp.toString(), rawPayload);

    CallbackCommand cmd =
        new CallbackCommand(
            corrId,
            f.connectorKey(),
            "evt-" + UUID.randomUUID(),
            sig,
            timestamp,
            rawPayload,
            payload,
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    AtomicBoolean cancelWon = new AtomicBoolean(false);

    // Thread 1: Cancel node
    executor.submit(
        () -> {
          try {
            startLatch.await();
            // simulate cancellation
            NodeExecution n = nodeExecutionRepository.findById(f.nodeExecutionId()).orElseThrow();
            if (n.getStatus() == NodeExecutionStatus.WAITING) {
              n.cancel(Instant.now());
              nodeExecutionRepository.saveAndFlush(n);
              cancelWon.set(true);
            }
          } catch (Exception ignored) {
          } finally {
            doneLatch.countDown();
          }
        });

    // Thread 2: Process callback
    executor.submit(
        () -> {
          try {
            startLatch.await();
            callbackCorrelationService.processCallback(cmd);
          } catch (Exception ignored) {
          } finally {
            doneLatch.countDown();
          }
        });

    startLatch.countDown();
    boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    executor.shutdown();

    // Verify invariants: Node is either CANCELLED or COMPLETED, never in inconsistent state
    NodeExecution finalNode = nodeExecutionRepository.findById(f.nodeExecutionId()).orElseThrow();
    assertThat(finalNode.getStatus())
        .isIn(NodeExecutionStatus.CANCELLED, NodeExecutionStatus.COMPLETED);

    if (finalNode.getStatus() == NodeExecutionStatus.CANCELLED) {
      // If cancel won, callback must have been audited as LATE
      IntegrationCallback cb = callbackRepository.findByCallbackCorrelationId(corrId).orElse(null);
      if (cb != null) {
        assertThat(cb.getStatus()).isEqualTo(IntegrationCallbackStatus.LATE);
      }
    }
  }

  @Test
  void testHttpEndpoint_validAndDuplicateAndInvalidSig() throws Exception {
    Fixture f = setupWaitingFixture("HTTP_API");
    String corrId = f.callbackCorrelationId();

    ObjectNode body = objectMapper.createObjectNode();
    body.put("status", "SUCCESS");
    body.put("txId", "TX_1001");
    String rawBody = body.toString();
    Instant timestamp = Instant.now();
    String sig = signatureValidator.computeSignature(signingSecret, timestamp.toString(), rawBody);

    // 1. Invalid signature -> 401 UNAUTHORIZED
    mockMvc
        .perform(
            post("/api/v1/callbacks/" + corrId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Connector-Key", f.connectorKey())
                .header("X-External-Event-Id", "ext-http-invalid-001")
                .header("X-Signature", "wrong-sig")
                .header("X-Timestamp", timestamp.toString())
                .content(rawBody))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    // 2. Valid signature -> 200 OK ACCEPTED
    mockMvc
        .perform(
            post("/api/v1/callbacks/" + corrId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Connector-Key", f.connectorKey())
                .header("X-External-Event-Id", "ext-http-001")
                .header("X-Signature", sig)
                .header("X-Timestamp", timestamp.toString())
                .content(rawBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    // 3. Duplicate external event -> 200 OK DUPLICATE
    mockMvc
        .perform(
            post("/api/v1/callbacks/" + corrId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Connector-Key", f.connectorKey())
                .header("X-External-Event-Id", "ext-http-001")
                .header("X-Signature", sig)
                .header("X-Timestamp", timestamp.toString())
                .content(rawBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DUPLICATE"));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Fixture setup helper
  // ────────────────────────────────────────────────────────────────────────────

  private record Fixture(
      Event event,
      UUID nodeExecutionId,
      UUID executionId,
      String callbackCorrelationId,
      String connectorKey,
      UUID successEndNodeId,
      UUID errorEndNodeId) {}

  private Fixture setupWaitingFixture(String actionKey) {
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

    // 1. Connector Definition, Action, Version
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
    retryPolicy.put("idempotent", true);
    retryPolicy.put("maxRetries", 1);
    retryPolicy.put("backoffMs", 10);
    ArrayNode errArray = retryPolicy.putArray("retryableErrors");
    errArray.add("503");

    ObjectNode executionConfig = objectMapper.createObjectNode();
    executionConfig.put("pattern", "ASYNC_CALLBACK");

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
            executionConfig,
            objectMapper.createObjectNode(),
            TECH_ADMIN);

    // 2. Workflow Definition + Version
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Async Action Workflow', 'ACTIVE', ?, ?, now(), now())",
        defId,
        "wf-cbk-" + suffix,
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
            Instant.now());
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

    // 8. Put sysActionExecution into WAITING state with reason EXTERNAL_CALLBACK
    sysActionExecution.start(Instant.now());
    sysActionExecution.waitFor(RuntimeWaitReason.EXTERNAL_CALLBACK);
    nodeExecutionRepository.saveAndFlush(sysActionExecution);

    // 9. IntegrationExecution in WAITING_CALLBACK state with callbackCorrelationId
    UUID executionId = UUID.randomUUID();
    String callbackCorrelationId = callbackCorrelationService.generateCorrelationId();
    IntegrationExecution execution =
        IntegrationExecution.createRunning(
            executionId,
            ev.getId(),
            sysActionExecution.getId(),
            connectorKey,
            actionKey,
            1,
            connectorActionVersion.getId(),
            connectorKey + "/" + actionKey + ":v1",
            "idemp:" + sysActionExecution.getId(),
            "{}",
            Instant.now());
    execution.markWaitingCallback(callbackCorrelationId, Instant.now());
    executionRepository.saveAndFlush(execution);

    return new Fixture(
        ev,
        sysActionExecution.getId(),
        executionId,
        callbackCorrelationId,
        connectorKey,
        successEndId,
        errorEndId);
  }
}
