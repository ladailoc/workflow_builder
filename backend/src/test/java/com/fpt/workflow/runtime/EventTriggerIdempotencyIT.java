package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.runtime.trigger.EventTriggerService;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EventTriggerIdempotencyIT {

  private static final UUID ACTOR = UUID.fromString("20000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_trigger_idempotency_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private EventTriggerService triggers;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void concurrentSameTypeAndCorrelationCreatesOneEventAndReplaysStableResult() throws Exception {
    Fixture fixture = fixture();
    String correlation = "external-" + UUID.randomUUID();
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);

    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> raceCreate(fixture, "WEBHOOK", correlation, ready, go));
      var second = pool.submit(() -> raceCreate(fixture, "WEBHOOK", correlation, ready, go));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();

      EventTriggerService.TriggerResult one = first.get(10, TimeUnit.SECONDS);
      EventTriggerService.TriggerResult two = second.get(10, TimeUnit.SECONDS);
      assertThat(one.event().getId()).isEqualTo(two.event().getId());
      assertThat(java.util.List.of(one.created(), two.created()))
          .containsExactlyInAnyOrder(true, false);
      assertThat(count("WEBHOOK", correlation)).isEqualTo(1);

      EventTriggerService.TriggerResult replay = create(fixture, "WEBHOOK", correlation);
      assertThat(replay.created()).isFalse();
      assertThat(replay.event().getId()).isEqualTo(one.event().getId());
    }
  }

  @Test
  void sameCorrelationAcrossTriggerTypesAndNullCorrelationsRemainIndependent() {
    String correlation = "shared-" + UUID.randomUUID();

    assertThat(create(fixture(), "WEBHOOK", correlation).created()).isTrue();
    assertThat(create(fixture(), "SCHEDULE", correlation).created()).isTrue();
    assertThat(create(fixture(), "WEBHOOK", null).created()).isTrue();
    assertThat(create(fixture(), "WEBHOOK", null).created()).isTrue();

    assertThat(count("WEBHOOK", correlation)).isEqualTo(1);
    assertThat(count("SCHEDULE", correlation)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from events where trigger_type = 'WEBHOOK' and trigger_correlation_key is null",
                Long.class))
        .isEqualTo(2L);
  }

  @Test
  void databaseConstraintRejectsDuplicateEvenWhenApplicationServiceIsBypassed() {
    Fixture fixture = fixture();
    String correlation = "db-guard-" + UUID.randomUUID();
    UUID originalEventId = create(fixture, "WEBHOOK", correlation).event().getId();
    jdbc.update(
        "update events set status = 'COMPLETED', outcome = 'DONE', ended_at = now() where id = ?",
        originalEventId);

    assertThatThrownBy(
            () -> {
              UUID duplicateId = UUID.randomUUID();
                jdbc.update(
                    "INSERT INTO events (id, ticket_id, workflow_version_id, started_ticket_revision_id, event_type, status, root_event_id, trigger_type, trigger_correlation_key, variables_json, started_by, started_at) VALUES (?, ?, ?, ?, 'ROOT', 'CREATED', ?, 'WEBHOOK', ?, '{}'::jsonb, ?, now())",
                    duplicateId,
                    fixture.ticketId(),
                    fixture.versionId(),
                    fixture.revisionId(),
                    duplicateId,
                    correlation,
                    ACTOR);
            })
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_events_trigger_correlation");
  }

  private EventTriggerService.TriggerResult raceCreate(
      Fixture fixture,
      String type,
      String correlation,
      CountDownLatch ready,
      CountDownLatch go)
      throws InterruptedException {
    ready.countDown();
    if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("race did not start");
    return create(fixture, type, correlation);
  }

  private EventTriggerService.TriggerResult create(
      Fixture fixture, String type, String correlation) {
    return triggers.createRoot(
        fixture.ticketId(),
        fixture.versionId(),
        fixture.revisionId(),
        null,
        null,
        type,
        correlation,
        JsonNodeFactory.instance.objectNode(),
        ACTOR,
        Instant.now());
  }

  private long count(String type, String correlation) {
    return jdbc.queryForObject(
        "select count(*) from events where trigger_type = ? and trigger_correlation_key = ?",
        Long.class,
        type,
        correlation);
  }

  private Fixture fixture() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID definitionId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID requestTypeId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) VALUES (?, ?, 'Trigger Test', 'ACTIVE', ?, ?, now(), now())",
        definitionId,
        "trigger-" + suffix,
        ACTOR,
        ACTOR);
    jdbc.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, status, revision, checksum, execution_package_json, created_by, created_at, published_by, published_at) VALUES (?, ?, 1, 'PUBLISHED', 0, 'trigger-test', '{}'::jsonb, ?, now(), ?, now())",
        versionId,
        definitionId,
        ACTOR,
        ACTOR);
    jdbc.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, creation_policy_json, created_at, updated_at) VALUES (?, ?, 'Trigger Request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        requestTypeId,
        "trigger-request-" + suffix,
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
    return new Fixture(ticketId, versionId, revisionId);
  }

  private record Fixture(UUID ticketId, UUID versionId, UUID revisionId) {}
}
