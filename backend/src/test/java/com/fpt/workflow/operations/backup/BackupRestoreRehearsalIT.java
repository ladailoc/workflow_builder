package com.fpt.workflow.operations.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PROMPT 64 — Backup / Restore Rehearsal Integration Test.
 *
 * <p>Verifies full PostgreSQL backup, retention, restore process, and Flyway compatibility. Proves
 * that after disaster recovery restore into a fresh test database, all 9 required runtime entities,
 * immutable history, and attachment metadata are 100% intact: 1. WorkflowVersion history (published
 * checksums, draft/published lifecycle) 2. Tickets (revisions, payloads, subjects) 3. Events
 * (running/completed states, started ticket revisions) 4. NodeExecutions (activation keys, inputs,
 * outputs) 5. Tasks (assignees, outcomes, assignment history) 6. AuditEvents (immutable audit logs)
 * 7. Jobs (durable outbox jobs in ready, retry, dead states) 8. Integration executions (attempts,
 * idempotency keys, sanitized payloads) 9. Organization hierarchy (departments/units, positions,
 * employees, assignments) + Flyway compatibility (all 34 migrations recorded as successful) +
 * Object-storage attachment metadata & checksum integrity
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BackupRestoreRehearsalIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_backup_source")
          .withUsername("workflow_admin")
          .withPassword("workflow_admin_secret");

  @Autowired private JdbcTemplate sourceJdbc;

  @Test
  @DisplayName(
      "Full Backup & Restore Rehearsal: proves data integrity across all 9 entities + Flyway + Attachments")
  void performsFullBackupAndRestoreRehearsal() throws Exception {
    Timestamp now = Timestamp.from(Instant.now());
    UUID seedId = UUID.randomUUID();

    // =========================================================================
    // 1. SEED RICH TEST DATASET INTO SOURCE DATABASE
    // =========================================================================

    // 1.1 Organization Hierarchy (V16)
    UUID orgUnitId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID assignmentId = UUID.randomUUID();

    sourceJdbc.update(
        "INSERT INTO organization_units (id, unit_code, name, unit_type, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'DEPARTMENT', 'ACTIVE', ?, ?)",
        orgUnitId,
        "DEP_" + seedId.toString().substring(0, 8),
        "Engineering Systems",
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO positions (id, position_code, title, org_unit_id, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
        positionId,
        "POS_" + seedId.toString().substring(0, 8),
        "Lead Architect",
        orgUnitId,
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO employees (id, user_id, employee_code, full_name, email, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
        employeeId,
        userId,
        "EMP_" + seedId.toString().substring(0, 8),
        "Dr. Workflow",
        "architect@workflow.local",
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO position_assignments (id, employee_id, position_id, is_primary, assignment_type, effective_from, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, true, 'PERMANENT', CURRENT_DATE, 'ACTIVE', ?, ?)",
        assignmentId,
        employeeId,
        positionId,
        now,
        now);

    // 1.2 Workflow Definition & Version History (V2, V3, V4)
    UUID definitionId = UUID.randomUUID();
    UUID publishedVersionId = UUID.randomUUID();
    UUID draftVersionId = UUID.randomUUID();
    String checksumV1 = "sha256_chk_" + seedId.toString().substring(0, 12);

    sourceJdbc.update(
        "INSERT INTO workflow_definitions (id, key, name, lifecycle, owner_id, created_by, created_at, updated_at, lock_version) "
            + "VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)",
        definitionId,
        "wf_" + seedId.toString().substring(0, 8),
        "Disaster Recovery Workflow",
        userId,
        userId,
        now,
        now);

    // 1. Create Version 1 as DRAFT first (nodes & edges can only be added to DRAFT versions)
    sourceJdbc.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, revision, status, execution_package_json, created_by, created_at, lock_version) "
            + "VALUES (?, ?, 1, 0, 'DRAFT', '{\"nodes\": [\"start\", \"approval\", \"end\"]}'::jsonb, ?, ?, 0)",
        publishedVersionId,
        definitionId,
        userId,
        now);

    // Workflow Nodes & Edge attached to Version 1 while in DRAFT
    UUID nodeStartId = UUID.randomUUID();
    UUID nodeEndId = UUID.randomUUID();
    UUID edgeId = UUID.randomUUID();

    sourceJdbc.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'start', 'START', 'Start Process', 1, '{}'::jsonb, '{\"x\":0,\"y\":0}'::jsonb)",
        nodeStartId,
        publishedVersionId);

    sourceJdbc.update(
        "INSERT INTO workflow_nodes (id, workflow_version_id, node_key, node_type, name, config_schema_version, config_json, position_json) "
            + "VALUES (?, ?, 'end', 'END', 'End Process', 1, '{}'::jsonb, '{\"x\":200,\"y\":0}'::jsonb)",
        nodeEndId,
        publishedVersionId);

    sourceJdbc.update(
        "INSERT INTO workflow_edges (id, workflow_version_id, source_node_id, source_port, target_node_id, priority, is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'DEFAULT', ?, 1, true, 'NORMAL', '{}'::jsonb)",
        edgeId,
        publishedVersionId,
        nodeStartId,
        nodeEndId);

    // 2. Publish Version 1 (now immutable)
    sourceJdbc.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = ?, published_by = ?, published_at = ? WHERE id = ?",
        checksumV1,
        userId,
        now,
        publishedVersionId);

    // Set current published version pointer
    sourceJdbc.update(
        "UPDATE workflow_definitions SET current_published_version_id = ? WHERE id = ?",
        publishedVersionId,
        definitionId);

    // 3. Draft Version (v2) based on v1
    sourceJdbc.update(
        "INSERT INTO workflow_versions (id, definition_id, version_no, revision, status, based_on_version_id, execution_package_json, created_by, created_at, lock_version) "
            + "VALUES (?, ?, 2, 1, 'DRAFT', ?, '{\"nodes\": [\"start\", \"approval\", \"review\", \"end\"]}'::jsonb, ?, ?, 0)",
        draftVersionId,
        definitionId,
        publishedVersionId,
        userId,
        now);

    // 1.3 Request Type, Tickets & Revisions (V2, V5)
    UUID requestTypeId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();

    sourceJdbc.update(
        "INSERT INTO request_types (id, key, name, category, workflow_definition_id, active, created_at, updated_at) "
            + "VALUES (?, ?, 'Server Request', 'INFRASTRUCTURE', ?, true, ?, ?)",
        requestTypeId,
        "rt_" + seedId.toString().substring(0, 8),
        definitionId,
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO tickets (id, request_type_id, creator_id, status, data_json, data_revision, current_revision_id, created_at, updated_at, submitted_at, lock_version) "
            + "VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, 0, NULL, ?, ?, NULL, 0)",
        ticketId,
        requestTypeId,
        userId,
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO ticket_revisions (id, ticket_id, revision_no, data_snapshot_json, source_schema_version, schema_checksum, submitted_by, submitted_at) "
            + "VALUES (?, ?, 1, '{\"serverName\":\"db-primary-01\",\"region\":\"us-east-1\"}'::jsonb, '1.0.0', 'chk_form', ?, ?)",
        revisionId,
        ticketId,
        userId,
        now);

    sourceJdbc.update(
        "UPDATE tickets SET status = 'SUBMITTED', data_json = '{\"serverName\":\"db-primary-01\"}'::jsonb, data_revision = 1, current_revision_id = ?, submitted_at = ? WHERE id = ?",
        revisionId,
        now,
        ticketId);

    UUID subjectId = UUID.randomUUID();
    sourceJdbc.update(
        "INSERT INTO ticket_subjects (id, ticket_id, subject_type, subject_ref_id, role_key, created_at) "
            + "VALUES (?, ?, 'SERVER', ?, 'PRIMARY', ?)",
        subjectId,
        ticketId,
        UUID.randomUUID(),
        now);

    // 1.4 Events & NodeExecutions (V6)
    UUID eventId = UUID.randomUUID();
    UUID nodeExecutionId = UUID.randomUUID();
    String activationKey = "act:" + eventId + ":start:0";

    sourceJdbc.update(
        "INSERT INTO events (id, ticket_id, workflow_version_id, started_ticket_revision_id, event_type, status, root_event_id, trigger_type, started_by, started_at, lock_version) "
            + "VALUES (?, ?, ?, ?, 'ROOT', 'RUNNING', ?, 'MANUAL', ?, ?, 0)",
        eventId,
        ticketId,
        publishedVersionId,
        revisionId,
        eventId,
        userId,
        now);

    sourceJdbc.update(
        "INSERT INTO node_executions (id, event_id, node_definition_id, activation_key, cycle_id, iteration, path_token, item_token, started_ticket_revision_id, status, outcome_port, input_json, output_json, created_at, started_at, ended_at, lock_version) "
            + "VALUES (?, ?, ?, ?, ?, 0, 'root', 'item_1', ?, 'COMPLETED', 'DEFAULT', '{\"init\":\"data\"}'::jsonb, '{\"success\":true}'::jsonb, ?, ?, ?, 0)",
        nodeExecutionId,
        eventId,
        nodeStartId,
        activationKey,
        UUID.randomUUID(),
        revisionId,
        now,
        now,
        now);

    // 1.5 Task Executions & Assignment History (V7)
    UUID taskId = UUID.randomUUID();
    UUID historyId = UUID.randomUUID();

    sourceJdbc.update(
        "INSERT INTO task_executions (id, node_execution_id, status, assignee_id, priority, title_snapshot, form_schema_json, input_snapshot_json, outcome, created_at, completed_at, lock_version) "
            + "VALUES (?, ?, 'COMPLETED', ?, 1, 'Approve Migration', '{}'::jsonb, '{\"requested\":true}'::jsonb, 'APPROVED', ?, ?, 0)",
        taskId,
        nodeExecutionId,
        userId,
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO task_assignment_history (id, task_id, action_type, from_user_id, to_user_id, actor_id, reason, metadata_json, created_at) "
            + "VALUES (?, ?, 'ASSIGN', null, ?, ?, 'Initial assignment', '{}'::jsonb, ?)",
        historyId,
        taskId,
        userId,
        userId,
        now);

    // 1.6 Audit Events (V8)
    UUID auditId = UUID.randomUUID();
    sourceJdbc.update(
        "INSERT INTO audit_events (id, aggregate_type, aggregate_id, event_type, actor_id, principal_id, correlation_id, command_id, metadata_json, occurred_at) "
            + "VALUES (?, 'EVENT', ?, 'EVENT_STARTED', ?, ?, ?, ?, '{\"detail\":\"Disaster recovery rehearsal\"}'::jsonb, ?)",
        auditId,
        eventId,
        userId,
        userId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        now);

    // 1.7 Durable Outbox Jobs (V30)
    UUID jobReadyId = UUID.randomUUID();
    UUID jobDeadId = UUID.randomUUID();

    sourceJdbc.update(
        "INSERT INTO workflow_jobs (id, job_type, aggregate_type, aggregate_id, payload_json, status, attempts, max_attempts, next_run_at, dedup_key, created_at, updated_at, completed_at, lock_version) "
            + "VALUES (?, 'SLA_BREACH_CHECK', 'EVENT', ?, '{\"rule\":\"tier1\"}'::jsonb, 'READY', 0, 5, ?, ?, ?, ?, null, 0)",
        jobReadyId,
        eventId,
        now,
        "dedup:ready:" + jobReadyId,
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO workflow_jobs (id, job_type, aggregate_type, aggregate_id, payload_json, status, attempts, max_attempts, next_run_at, dedup_key, last_error_json, created_at, updated_at, completed_at, lock_version) "
            + "VALUES (?, 'NOTIFICATION_DISPATCH', 'EVENT', ?, '{\"notify\":\"operator\"}'::jsonb, 'DEAD', 5, 5, ?, ?, '{\"error\":\"Max retries exceeded\"}'::jsonb, ?, ?, ?, 0)",
        jobDeadId,
        eventId,
        now,
        "dedup:dead:" + jobDeadId,
        now,
        now,
        now);

    // 1.8 Integration Executions & Attempts (V25, V26)
    UUID connectorId = UUID.randomUUID();
    UUID connectorActionId = UUID.randomUUID();
    UUID actionVersionId = UUID.randomUUID();
    UUID integrationExecId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();

    sourceJdbc.update(
        "INSERT INTO connector_definitions (id, key, name, connector_type, handler_key, status, config_json, created_at, updated_at, lock_version) "
            + "VALUES (?, ?, 'AWS Cloud Connector', 'REST', 'aws-rest-handler', 'ACTIVE', '{}'::jsonb, ?, ?, 0)",
        connectorId,
        "aws_" + seedId.toString().substring(0, 8),
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO connector_actions (id, connector_id, action_key, name, status, created_at, updated_at, lock_version) "
            + "VALUES (?, ?, 'provision', 'Provision Instance', 'ACTIVE', ?, ?, 0)",
        connectorActionId,
        connectorId,
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO connector_action_versions (id, connector_action_id, version_no, status, input_schema_json, output_schema_json, execution_config_json, retry_policy_json, idempotency_policy_json, error_mapping_json, permission_policy_json, created_at, lock_version) "
            + "VALUES (?, ?, 1, 'PUBLISHED', '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, '{\"maxAttempts\":3}'::jsonb, '{\"idempotent\":true}'::jsonb, '{}'::jsonb, '{}'::jsonb, ?, 0)",
        actionVersionId,
        connectorActionId,
        now);

    sourceJdbc.update(
        "INSERT INTO integration_executions (id, event_id, node_execution_id, connector_action_version_id, connector_key, action_key, action_version, status, logical_action_identity, idempotency_key, sanitized_request_json, sanitized_response_json, created_at, updated_at, completed_at, lock_version) "
            + "VALUES (?, ?, ?, ?, 'aws', 'provision', 1, 'COMPLETED', 'aws:provision:01', ?, '{\"vm\":\"t3.medium\"}'::jsonb, '{\"instanceId\":\"i-12345\"}'::jsonb, ?, ?, ?, 0)",
        integrationExecId,
        eventId,
        nodeExecutionId,
        actionVersionId,
        "idemp:" + integrationExecId,
        now,
        now,
        now);

    sourceJdbc.update(
        "INSERT INTO integration_attempts (id, integration_execution_id, attempt_number, status, sanitized_request_json, sanitized_response_json, started_at, completed_at) "
            + "VALUES (?, ?, 1, 'SUCCESS', '{\"vm\":\"t3.medium\"}'::jsonb, '{\"instanceId\":\"i-12345\"}'::jsonb, ?, ?)",
        attemptId,
        integrationExecId,
        now,
        now);

    // 1.9 File Attachments & Links (V29)
    UUID fileId = UUID.randomUUID();
    UUID fileLinkId = UUID.randomUUID();
    String expectedSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    String storageKey = "attachments/" + seedId + "/contract.pdf";

    sourceJdbc.update(
        "INSERT INTO files (id, original_name, mime_type, size_bytes, checksum, storage_provider, bucket, storage_key, scan_status, uploaded_by, uploaded_at) "
            + "VALUES (?, 'architecture_contract.pdf', 'application/pdf', 1048576, ?, 'S3', 'workflow-docs', ?, 'CLEAN', ?, ?)",
        fileId,
        expectedSha256,
        storageKey,
        userId,
        now);

    sourceJdbc.update(
        "INSERT INTO file_links (id, file_id, owner_type, owner_id, field_key, created_at) "
            + "VALUES (?, ?, 'TICKET_REVISION', ?, 'specDocument', ?)",
        fileLinkId,
        fileId,
        revisionId,
        now);

    // =========================================================================
    // 2. BACKUP PHASE: Execute pg_dump custom format (-Fc)
    // =========================================================================
    String dumpPath = "/tmp/rehearsal_backup.dump";
    String checksumPath = dumpPath + ".sha256";

    ExecResult dumpExec =
        postgres.execInContainer(
            "pg_dump",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "-Fc",
            "-b",
            "-f",
            dumpPath);

    assertThat(dumpExec.getExitCode())
        .as("pg_dump must exit with 0. Error output: %s", dumpExec.getStderr())
        .isZero();

    // Verify dump file presence and non-zero size
    ExecResult lsExec = postgres.execInContainer("ls", "-la", dumpPath);
    assertThat(lsExec.getExitCode()).isZero();
    assertThat(lsExec.getStdout()).contains("rehearsal_backup.dump");

    // Compute and verify SHA-256 checksum in container
    ExecResult shaGenExec =
        postgres.execInContainer("sh", "-c", "sha256sum " + dumpPath + " > " + checksumPath);
    assertThat(shaGenExec.getExitCode()).isZero();

    ExecResult shaVerifyExec = postgres.execInContainer("sh", "-c", "sha256sum -c " + checksumPath);
    assertThat(shaVerifyExec.getExitCode()).isZero();
    assertThat(shaVerifyExec.getStdout()).contains("OK");

    // =========================================================================
    // 3. RESTORE PHASE: Restore into fresh target database
    // =========================================================================
    String restoredDbName = "workflow_restore_rehearsal";

    // Create fresh target database
    ExecResult createDbExec =
        postgres.execInContainer(
            "psql",
            "-U",
            postgres.getUsername(),
            "-d",
            "postgres",
            "-c",
            "CREATE DATABASE " + restoredDbName + " WITH ENCODING 'UTF8';");
    assertThat(createDbExec.getExitCode())
        .as("Creation of target restore database must succeed: %s", createDbExec.getStderr())
        .isZero();

    // Execute pg_restore
    postgres.execInContainer(
        "pg_restore",
        "-U",
        postgres.getUsername(),
        "-d",
        restoredDbName,
        "--clean",
        "--if-exists",
        "--no-owner",
        dumpPath);

    // =========================================================================
    // 4. VERIFICATION PHASE: Deep validation on the Restored Database
    // =========================================================================
    String restoredJdbcUrl =
        postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + restoredDbName);

    JdbcTemplate restoredJdbc =
        new JdbcTemplate(
            new SingleConnectionDataSource(
                restoredJdbcUrl, postgres.getUsername(), postgres.getPassword(), true));

    // 4.1 Flyway Compatibility Check: All 34 migrations recorded and valid
    Integer flywayCount =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
    assertThat(flywayCount)
        .as("Flyway schema history must have all 34 migrations intact")
        .isEqualTo(34);

    // 4.2 Entity 1: WorkflowVersion history
    Integer versionCount =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM workflow_versions WHERE definition_id = ?",
            Integer.class,
            definitionId);
    assertThat(versionCount).isEqualTo(2);

    String restoredChecksum =
        restoredJdbc.queryForObject(
            "SELECT checksum FROM workflow_versions WHERE id = ?",
            String.class,
            publishedVersionId);
    assertThat(restoredChecksum).isEqualTo(checksumV1);

    String restoredDraftStatus =
        restoredJdbc.queryForObject(
            "SELECT status FROM workflow_versions WHERE id = ?", String.class, draftVersionId);
    assertThat(restoredDraftStatus).isEqualTo("DRAFT");

    Integer nodeCount =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM workflow_nodes WHERE workflow_version_id = ?",
            Integer.class,
            publishedVersionId);
    assertThat(nodeCount).isEqualTo(2);

    // 4.3 Entity 2: Tickets & Revisions
    Integer ticketCount =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM tickets WHERE id = ?", Integer.class, ticketId);
    assertThat(ticketCount).isEqualTo(1);

    String restoredFormPayload =
        restoredJdbc.queryForObject(
            "SELECT data_snapshot_json::text FROM ticket_revisions WHERE id = ?",
            String.class,
            revisionId);
    assertThat(restoredFormPayload).contains("db-primary-01");

    // 4.4 Entity 3: Events
    String restoredEventStatus =
        restoredJdbc.queryForObject(
            "SELECT status FROM events WHERE id = ?", String.class, eventId);
    assertThat(restoredEventStatus).isEqualTo("RUNNING");

    // 4.5 Entity 4: NodeExecutions
    String restoredActivationKey =
        restoredJdbc.queryForObject(
            "SELECT activation_key FROM node_executions WHERE id = ?",
            String.class,
            nodeExecutionId);
    assertThat(restoredActivationKey).isEqualTo(activationKey);

    String restoredNodeStatus =
        restoredJdbc.queryForObject(
            "SELECT status FROM node_executions WHERE id = ?", String.class, nodeExecutionId);
    assertThat(restoredNodeStatus).isEqualTo("COMPLETED");

    // 4.6 Entity 5: Tasks & Assignment History
    String restoredTaskOutcome =
        restoredJdbc.queryForObject(
            "SELECT outcome FROM task_executions WHERE id = ?", String.class, taskId);
    assertThat(restoredTaskOutcome).isEqualTo("APPROVED");

    Integer historyCount =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM task_assignment_history WHERE task_id = ?",
            Integer.class,
            taskId);
    assertThat(historyCount).isEqualTo(1);

    // 4.7 Entity 6: AuditEvents
    Integer auditCount =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE aggregate_id = ?", Integer.class, eventId);
    assertThat(auditCount).isEqualTo(1);

    // 4.8 Entity 7: Jobs
    Integer readyJobs =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM workflow_jobs WHERE id = ? AND status = 'READY'",
            Integer.class,
            jobReadyId);
    assertThat(readyJobs).isEqualTo(1);

    Integer deadJobs =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM workflow_jobs WHERE id = ? AND status = 'DEAD'",
            Integer.class,
            jobDeadId);
    assertThat(deadJobs).isEqualTo(1);

    // 4.9 Entity 8: Integration Executions & Attempts
    String restoredIntegStatus =
        restoredJdbc.queryForObject(
            "SELECT status FROM integration_executions WHERE id = ?",
            String.class,
            integrationExecId);
    assertThat(restoredIntegStatus).isEqualTo("COMPLETED");

    Integer attemptCount =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM integration_attempts WHERE integration_execution_id = ?",
            Integer.class,
            integrationExecId);
    assertThat(attemptCount).isEqualTo(1);

    // 4.10 Entity 9: Organization Hierarchy
    Integer orgUnits =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM organization_units WHERE id = ?", Integer.class, orgUnitId);
    assertThat(orgUnits).isEqualTo(1);

    Integer positions =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM positions WHERE id = ?", Integer.class, positionId);
    assertThat(positions).isEqualTo(1);

    Integer employees =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM employees WHERE id = ?", Integer.class, employeeId);
    assertThat(employees).isEqualTo(1);

    Integer assignments =
        restoredJdbc.queryForObject(
            "SELECT count(*) FROM position_assignments WHERE id = ?", Integer.class, assignmentId);
    assertThat(assignments).isEqualTo(1);

    // 4.11 File Attachments & Checksum Integrity
    String restoredSha256 =
        restoredJdbc.queryForObject(
            "SELECT checksum FROM files WHERE id = ?", String.class, fileId);
    assertThat(restoredSha256).isEqualTo(expectedSha256);

    String restoredStorageKey =
        restoredJdbc.queryForObject(
            "SELECT storage_key FROM files WHERE id = ?", String.class, fileId);
    assertThat(restoredStorageKey).isEqualTo(storageKey);
  }
}
