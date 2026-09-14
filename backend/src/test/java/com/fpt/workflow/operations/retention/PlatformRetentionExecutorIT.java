package com.fpt.workflow.operations.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.operations.job.WorkflowJobRepository;
import com.fpt.workflow.operations.job.WorkflowJobStatus;
import com.fpt.workflow.operations.job.WorkflowJobWorker;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.PermissionKey;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

/**
 * P2-15: platform retention execution — RetentionPlanner wired to an audited ADMIN-only flow with
 * dry-run preview; no-hard-delete guarantee (referenced history downgrades to ANONYMIZE; decisions
 * persisted in retention_actions).
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PlatformRetentionExecutorIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_platform_retention_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private PlatformRetentionExecutor executor;
  @Autowired private PlatformRetentionCommandService commandService;
  @Autowired private WorkflowJobWorker worker;
  @Autowired private WorkflowJobRepository jobs;
  @Autowired private RetentionPolicyRepository policies;
  @Autowired private RetentionActionRepository actions;
  @Autowired private AuditEventRepository audits;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  private ActorContext admin() {
    return new ActorContext(
        UUID.fromString("10000000-0000-4000-8000-000000001501"), "admin",
        Set.of(RoleKey.ADMIN), Set.of(PermissionKey.of("RETENTION_EXECUTE")));
  }

  @Test
  @com.fpt.workflow.security.testing.WithMockActor(roles = {"ADMIN", "OPERATOR"})
  void previewAndExecute_mutatesAppendOnlyAuditPayloadOnce() {
    Instant now = Instant.parse("2026-09-08T00:00:00Z");
    upsertAuditPolicy(now);

    UUID freshId = UUID.randomUUID();
    UUID staleId = UUID.randomUUID();
    audits.saveAndFlush(
        AuditEvent.record(
            staleId,
            "TICKET",
            UUID.randomUUID(),
            "TICKET_SUBMITTED",
            admin().actorId(),
            admin().actorId(),
            new CorrelationId(UUID.randomUUID()),
            new CommandId(UUID.randomUUID()),
            mapper
                .createObjectNode()
                .put("requester", "Alice")
                .put("amount", 1200)
                .set("nested", mapper.createObjectNode().put("email", "alice@example.test")),
            now.minusSeconds(400L * 24 * 3600)));
    PlatformRetentionExecutor.RetentionCandidate fresh =
        new PlatformRetentionExecutor.RetentionCandidate(
            RetentionCategory.AUDIT, "AUDIT_EVENT", freshId, now.minusSeconds(1000), false);
    PlatformRetentionExecutor.RetentionCandidate staleReferenced =
        new PlatformRetentionExecutor.RetentionCandidate(
            RetentionCategory.AUDIT, "AUDIT_EVENT", staleId, now.minusSeconds(400L * 24 * 3600), true);

    PlatformRetentionExecutor.RetentionPreview preview =
        executor.preview(List.of(fresh, staleReferenced));
    assertThat(preview.rows())
        .extracting(PlatformRetentionExecutor.RetentionPreviewRow::decision)
        .containsExactly(RetentionDecision.RETAIN, RetentionDecision.MASK);

    PlatformRetentionExecutor.RetentionExecutionResult result =
        executor.execute(
            List.of(fresh, staleReferenced), admin(),
            new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));
    assertThat(result.executedCount()).isEqualTo(1);
    assertThat(actions.findAllByAggregateIdOrderByDecidedAtAsc(staleId))
        .extracting(RetentionAction::getAction)
        .containsExactly("MASK");
    assertThat(
            jdbc.queryForObject(
                "SELECT metadata_json::text FROM audit_events WHERE id=?", String.class, staleId))
        .contains("***MASKED***")
        .contains("\"amount\": 1200");
    assertThat(
            audits.findAll().stream()
                .anyMatch(a -> "RETENTION_EXECUTED".equals(a.getEventType())))
        .isTrue();

    PlatformRetentionExecutor.RetentionExecutionResult replay =
        executor.execute(
            List.of(staleReferenced), admin(),
            new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));
    assertThat(replay.executedCount()).isZero();
    assertThat(actions.findAllByAggregateIdOrderByDecidedAtAsc(staleId)).hasSize(1);
  }

  @Test
  @com.fpt.workflow.security.testing.WithMockActor(roles = {"ADMIN"})
  void legalHoldSuppressesMutationAndActionRow() {
    Instant now = Instant.parse("2026-09-08T00:00:00Z");
    upsertAuditPolicy(now);
    UUID heldId = UUID.randomUUID();
    audits.saveAndFlush(
        AuditEvent.record(
            heldId,
            "TICKET",
            UUID.randomUUID(),
            "TICKET_SUBMITTED",
            admin().actorId(),
            admin().actorId(),
            new CorrelationId(UUID.randomUUID()),
            new CommandId(UUID.randomUUID()),
            mapper.createObjectNode().put("requester", "Bob"),
            now.minusSeconds(400L * 24 * 3600)));
    jdbc.update(
        "INSERT INTO retention_legal_holds (id,category,aggregate_type,aggregate_id,reason,held_by,held_at) VALUES (?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        "AUDIT",
        "AUDIT_EVENT",
        heldId,
        "legal discovery",
        admin().actorId(),
        java.sql.Timestamp.from(now));

    PlatformRetentionExecutor.RetentionCandidate held =
        new PlatformRetentionExecutor.RetentionCandidate(
            RetentionCategory.AUDIT, "AUDIT_EVENT", heldId, now.minusSeconds(400L * 24 * 3600), true);

    assertThat(executor.preview(List.of(held)).rows().getFirst().decision())
        .isEqualTo(RetentionDecision.RETAIN);
    assertThat(
            executor
                .execute(
                    List.of(held),
                    admin(),
                    new CorrelationId(UUID.randomUUID()),
                    new CommandId(UUID.randomUUID()))
                .executedCount())
        .isZero();
    assertThat(actions.findAllByAggregateIdOrderByDecidedAtAsc(heldId)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT metadata_json::text FROM audit_events WHERE id=?", String.class, heldId))
        .contains("Bob");
  }

  @Test
  @com.fpt.workflow.security.testing.WithMockActor(roles = {"OPERATOR"})
  void adminExecuteEndpointPathEnqueuesDurableJobAndWorkerMutatesPayload() {
    Instant now = Instant.parse("2026-09-08T00:00:00Z");
    upsertAuditPolicy(now);
    UUID auditId = UUID.randomUUID();
    audits.saveAndFlush(
        AuditEvent.record(
            auditId,
            "TICKET",
            UUID.randomUUID(),
            "TICKET_SUBMITTED",
            admin().actorId(),
            admin().actorId(),
            new CorrelationId(UUID.randomUUID()),
            new CommandId(UUID.randomUUID()),
            mapper.createObjectNode().put("requester", "Cara"),
            now.minusSeconds(400L * 24 * 3600)));
    PlatformRetentionExecutor.RetentionCandidate candidate =
        new PlatformRetentionExecutor.RetentionCandidate(
            RetentionCategory.AUDIT, "AUDIT_EVENT", auditId, now.minusSeconds(400L * 24 * 3600), true);

    PlatformRetentionCommandService.RetentionJobSubmission submission =
        commandService.enqueue(
            List.of(candidate), new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));

    assertThat(worker.runOnce("retention-worker", 5, Duration.ofMinutes(1))).isEqualTo(1);
    assertThat(jobs.findById(submission.jobId()).orElseThrow().getStatus())
        .isEqualTo(WorkflowJobStatus.COMPLETED);
    assertThat(actions.findAllByAggregateIdOrderByDecidedAtAsc(auditId))
        .extracting(RetentionAction::getAction)
        .containsExactly("MASK");
    assertThat(
            jdbc.queryForObject(
                "SELECT metadata_json::text FROM audit_events WHERE id=?", String.class, auditId))
        .contains("***MASKED***");
  }

  private void upsertAuditPolicy(Instant now) {
    jdbc.update(
        """
        INSERT INTO retention_policies (id,category,retention_days,expiry_action,enabled,created_at,updated_at)
        VALUES (?,?,?,?,?,?,?)
        ON CONFLICT (category) DO UPDATE
        SET retention_days=EXCLUDED.retention_days,
            expiry_action=EXCLUDED.expiry_action,
            enabled=EXCLUDED.enabled,
            updated_at=EXCLUDED.updated_at,
            lock_version=retention_policies.lock_version+1
        """,
        UUID.randomUUID(),
        "AUDIT",
        365,
        "MASK",
        true,
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
  }
}
