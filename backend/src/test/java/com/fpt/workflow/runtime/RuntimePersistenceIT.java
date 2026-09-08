package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RuntimePersistenceIT {

  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_runtime_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private EventRepository eventRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void acceptsMultipleOccurrencesOfTheSameNodeDefinition() {
    Fixture fixture = fixture("occurrences", true);
    Event event = eventRepository.saveAndFlush(rootEvent(fixture, "trigger-occurrences"));

    NodeExecution first = occurrence(fixture, event.getId(), "activation-occurrence-1", 0);
    NodeExecution second = occurrence(fixture, event.getId(), "activation-occurrence-2", 1);
    nodeExecutionRepository.saveAndFlush(first);
    nodeExecutionRepository.saveAndFlush(second);

    assertThat(
            nodeExecutionRepository.findAllByEventIdAndNodeDefinitionIdOrderByCreatedAtAsc(
                event.getId(), fixture.nodeId()))
        .extracting(NodeExecution::getActivationKey)
        .containsExactlyInAnyOrder("activation-occurrence-1", "activation-occurrence-2");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint "
                    + "WHERE conrelid = 'node_executions'::regclass "
                    + "AND contype = 'u' "
                    + "AND pg_get_constraintdef(oid) ILIKE '%event_id, node_definition_id%'",
                Integer.class))
        .isZero();
  }

  @Test
  void rejectsDuplicateActivationKey() {
    Fixture fixture = fixture("activation", true);
    Event event = eventRepository.saveAndFlush(rootEvent(fixture, "trigger-activation"));
    nodeExecutionRepository.saveAndFlush(
        occurrence(fixture, event.getId(), "stable-activation-key", 0));

    assertThatThrownBy(
            () ->
                nodeExecutionRepository.saveAndFlush(
                    occurrence(fixture, event.getId(), "stable-activation-key", 1)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void enforcesExactPublishedVersionAndRuntimeOwnership() {
    Fixture fixture = fixture("version-a", true);
    Fixture other = fixture("version-b", true);
    Event event = eventRepository.saveAndFlush(rootEvent(fixture, "trigger-version"));

    assertThatThrownBy(
            () ->
                eventRepository.saveAndFlush(
                    Event.createRoot(
                        UUID.randomUUID(),
                        fixture.ticketId(),
                        UUID.randomUUID(),
                        fixture.revisionId(),
                        null,
                        null,
                        "USER_SUBMIT",
                        "missing-version",
                        objectMapper.createObjectNode(),
                        ACTOR_ID,
                        NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID draftVersionId = addDraftVersion(fixture.definitionId());
    assertThatThrownBy(
            () ->
                eventRepository.saveAndFlush(
                    Event.createRoot(
                        UUID.randomUUID(),
                        fixture.ticketId(),
                        draftVersionId,
                        fixture.revisionId(),
                        null,
                        null,
                        "USER_SUBMIT",
                        "draft-version",
                        objectMapper.createObjectNode(),
                        ACTOR_ID,
                        NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                nodeExecutionRepository.saveAndFlush(
                    NodeExecution.create(
                        UUID.randomUUID(),
                        event.getId(),
                        other.nodeId(),
                        "cross-version-node",
                        UUID.randomUUID(),
                        0,
                        "root",
                        null,
                        null,
                        null,
                        objectMapper.createObjectNode(),
                        fixture.revisionId(),
                        NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void permitsOnlyOneActiveRootButAllowsChildAndLaterRootHistory() {
    Fixture fixture = fixture("root-constraint", true);
    Event firstRoot = eventRepository.saveAndFlush(rootEvent(fixture, "trigger-root-1"));
    NodeExecution parentNode =
        nodeExecutionRepository.saveAndFlush(
            occurrence(fixture, firstRoot.getId(), "parent-subworkflow-node", 0));
    Event child =
        Event.createChild(
            UUID.randomUUID(),
            fixture.ticketId(),
            fixture.versionId(),
            fixture.revisionId(),
            firstRoot.getId(),
            firstRoot.getId(),
            parentNode.getId(),
            "SUBWORKFLOW",
            "child-trigger",
            objectMapper.createObjectNode(),
            ACTOR_ID,
            NOW.plusSeconds(1));
    eventRepository.saveAndFlush(child);

    assertThatThrownBy(
            () -> eventRepository.saveAndFlush(rootEvent(fixture, "trigger-root-duplicate")))
        .isInstanceOf(DataIntegrityViolationException.class);

    firstRoot.cancel("WITHDRAWN", NOW.plusSeconds(2));
    eventRepository.saveAndFlush(firstRoot);
    Event nextRoot =
        Event.createRoot(
            UUID.randomUUID(),
            fixture.ticketId(),
            fixture.versionId(),
            fixture.revisionId(),
            firstRoot.getId(),
            firstRoot.getId(),
            "RESTART",
            "trigger-root-2",
            objectMapper.createObjectNode(),
            ACTOR_ID,
            NOW.plusSeconds(3));
    eventRepository.saveAndFlush(nextRoot);

    assertThat(eventRepository.findAllByTicketIdOrderByStartedAtAsc(fixture.ticketId())).hasSize(3);
  }

  @Test
  void preservesTerminalHistoryAndUsesOptimisticVersions() {
    Fixture fixture = fixture("history", true);
    Event event = eventRepository.saveAndFlush(rootEvent(fixture, "trigger-history"));
    NodeExecution node =
        nodeExecutionRepository.saveAndFlush(
            occurrence(fixture, event.getId(), "history-activation", 0));
    node.markReady();
    node.start(NOW);
    node.complete("SUCCESS", objectMapper.createObjectNode().put("done", true), NOW.plusSeconds(1));
    nodeExecutionRepository.saveAndFlush(node);
    event.markRunning();
    event.complete("SUCCESS", NOW.plusSeconds(2));
    eventRepository.saveAndFlush(event);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE node_executions SET status = 'RUNNING', ended_at = NULL WHERE id = ?",
                    node.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE events SET status = 'RUNNING', ended_at = NULL WHERE id = ?",
                    event.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM events WHERE id = ?", event.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM tickets WHERE id = ?", fixture.ticketId()))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM events WHERE id = ?", Integer.class, event.getId()))
        .isOne();

    Fixture optimisticFixture = fixture("optimistic", true);
    Event optimisticEvent =
        eventRepository.saveAndFlush(rootEvent(optimisticFixture, "trigger-optimistic"));
    Event firstCopy = eventRepository.findById(optimisticEvent.getId()).orElseThrow();
    Event staleCopy = eventRepository.findById(optimisticEvent.getId()).orElseThrow();
    firstCopy.markRunning();
    eventRepository.saveAndFlush(firstCopy);
    staleCopy.markRunning();

    assertThatThrownBy(() -> eventRepository.saveAndFlush(staleCopy))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void preservesFailedEventAsTerminalHistory() {
    Fixture fixture = fixture("failed-history", true);
    Event event = eventRepository.saveAndFlush(rootEvent(fixture, "trigger-failed-history"));
    event.markRunning();
    event.fail(NOW.plusSeconds(1));
    eventRepository.saveAndFlush(event);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE events SET status = 'RUNNING', ended_at = NULL WHERE id = ?",
                    event.getId()))
        .isInstanceOf(DataAccessException.class);
  }

  private Event rootEvent(Fixture fixture, String correlationKey) {
    UUID id = UUID.randomUUID();
    return Event.createRoot(
        id,
        fixture.ticketId(),
        fixture.versionId(),
        fixture.revisionId(),
        null,
        null,
        "USER_SUBMIT",
        correlationKey,
        objectMapper.createObjectNode(),
        ACTOR_ID,
        NOW);
  }

  private NodeExecution occurrence(
      Fixture fixture, UUID eventId, String activationKey, int iteration) {
    return NodeExecution.create(
        UUID.randomUUID(),
        eventId,
        fixture.nodeId(),
        activationKey,
        UUID.randomUUID(),
        iteration,
        "root-path",
        null,
        null,
        null,
        objectMapper.createObjectNode(),
        fixture.revisionId(),
        NOW);
  }

  private UUID addDraftVersion(UUID definitionId) {
    UUID versionId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workflow_versions "
            + "(id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 2, 'DRAFT', 0, ?, now())",
        versionId,
        definitionId,
        ACTOR_ID);
    return versionId;
  }

  private Fixture fixture(String label, boolean publishVersion) {
    String suffix = label + "-" + UUID.randomUUID().toString().substring(0, 8);
    UUID definitionId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    UUID requestTypeId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();

    jdbcTemplate.update(
        "INSERT INTO workflow_definitions "
            + "(id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Runtime workflow', 'ACTIVE', ?, ?, now(), now())",
        definitionId,
        "workflow-" + suffix,
        ACTOR_ID,
        ACTOR_ID);
    jdbcTemplate.update(
        "INSERT INTO workflow_versions "
            + "(id, definition_id, version_no, status, revision, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now())",
        versionId,
        definitionId,
        ACTOR_ID);
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes "
            + "(id, workflow_version_id, node_key, node_type, name, config_schema_version, "
            + "config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start', 1, '{}'::jsonb, '{}'::jsonb)",
        nodeId,
        versionId);
    if (publishVersion) {
      jdbcTemplate.update(
          "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', "
              + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() "
              + "WHERE id = ?",
          ACTOR_ID,
          versionId);
    }
    jdbcTemplate.update(
        "INSERT INTO request_types "
            + "(id, key, name, category, workflow_definition_id, active, "
            + "creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Runtime request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        requestTypeId,
        "request-" + suffix,
        definitionId);
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
    return new Fixture(definitionId, versionId, nodeId, ticketId, revisionId);
  }

  private record Fixture(
      UUID definitionId, UUID versionId, UUID nodeId, UUID ticketId, UUID revisionId) {}
}
