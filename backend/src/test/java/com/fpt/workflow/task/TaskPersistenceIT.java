package com.fpt.workflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.task.domain.TaskAssignmentAction;
import com.fpt.workflow.task.domain.TaskAssignmentHistory;
import com.fpt.workflow.task.domain.TaskCandidate;
import com.fpt.workflow.task.domain.TaskDecision;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskAssignmentHistoryRepository;
import com.fpt.workflow.task.repository.TaskCandidateRepository;
import com.fpt.workflow.task.repository.TaskDecisionRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
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
class TaskPersistenceIT {

  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID ASSIGNEE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_task_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private EventRepository eventRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private ParticipantSnapshotRepository participantSnapshotRepository;
  @Autowired private TaskExecutionRepository taskExecutionRepository;
  @Autowired private TaskCandidateRepository taskCandidateRepository;
  @Autowired private TaskAssignmentHistoryRepository assignmentHistoryRepository;
  @Autowired private TaskDecisionRepository taskDecisionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void supportsMultipleTasksAndCandidatesPerNodeExecution() {
    Fixture fixture = fixture("cardinality");
    TaskExecution first = task(fixture.nodeExecutionId(), ASSIGNEE_ID, "First review");
    TaskExecution second = task(fixture.nodeExecutionId(), UUID.randomUUID(), "Second review");
    taskExecutionRepository.saveAndFlush(first);
    taskExecutionRepository.saveAndFlush(second);

    UUID candidateOne = UUID.randomUUID();
    UUID candidateTwo = UUID.randomUUID();
    taskCandidateRepository.saveAndFlush(
        TaskCandidate.create(
            first.getId(),
            candidateOne,
            "ROLE",
            objectMapper.createObjectNode().put("role", "REVIEWER"),
            NOW));
    taskCandidateRepository.saveAndFlush(
        TaskCandidate.create(
            first.getId(),
            candidateTwo,
            "GROUP",
            objectMapper.createObjectNode().put("group", "FINANCE"),
            NOW.plusSeconds(1)));

    assertThat(
            taskExecutionRepository.findAllByNodeExecutionIdOrderByCreatedAtAsc(
                fixture.nodeExecutionId()))
        .extracting(TaskExecution::getId)
        .containsExactly(first.getId(), second.getId());
    assertThat(taskCandidateRepository.findAllByTaskIdOrderByCreatedAtAsc(first.getId()))
        .extracting(TaskCandidate::getUserId)
        .containsExactly(candidateOne, candidateTwo);
  }

  @Test
  void bindsParticipantSnapshotToTheExactEventAndNodeOccurrence() {
    Fixture fixture = fixture("participant-a");
    Fixture other = fixture("participant-b");
    ParticipantSnapshot snapshot =
        participantSnapshotRepository.saveAndFlush(participant(fixture, ASSIGNEE_ID));

    assertThatThrownBy(
            () ->
                participantSnapshotRepository.saveAndFlush(
                    ParticipantSnapshot.create(
                        UUID.randomUUID(),
                        other.eventId(),
                        fixture.nodeExecutionId(),
                        null,
                        "CREATOR_MANAGER",
                        "cross-event-config",
                        objectMapper.createObjectNode(),
                        ParticipantResolutionStatus.RESOLVED,
                        "EMPLOYEE",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "APPROVER",
                        objectMapper.createObjectNode(),
                        NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE participant_snapshots SET participant_role = 'OBSERVER' WHERE id = ?",
                    snapshot.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM participant_snapshots WHERE id = ?", snapshot.getId()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void keepsAssignmentAndDecisionHistoryAppendOnly() {
    Fixture fixture = fixture("append-only");
    TaskExecution task =
        taskExecutionRepository.saveAndFlush(
            task(fixture.nodeExecutionId(), ASSIGNEE_ID, "Append-only review"));
    TaskAssignmentHistory assignment =
        assignmentHistoryRepository.saveAndFlush(
            TaskAssignmentHistory.create(
                UUID.randomUUID(),
                task.getId(),
                TaskAssignmentAction.ASSIGN,
                null,
                ASSIGNEE_ID,
                ACTOR_ID,
                null,
                objectMapper.createObjectNode().put("source", "participant-snapshot"),
                NOW));
    task.complete(BusinessOutcome.APPROVED, NOW.plusSeconds(1));
    taskExecutionRepository.saveAndFlush(task);
    TaskDecision decision =
        taskDecisionRepository.saveAndFlush(
            TaskDecision.create(
                UUID.randomUUID(),
                task.getId(),
                UUID.randomUUID(),
                ASSIGNEE_ID,
                ACTOR_ID,
                BusinessOutcome.APPROVED,
                objectMapper.createObjectNode().put("approved", true),
                "Approved",
                NOW.plusSeconds(1)));

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE task_assignment_history SET reason = 'changed' WHERE id = ?",
                    assignment.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM task_assignment_history WHERE id = ?", assignment.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE task_decisions SET comment = 'changed' WHERE id = ?", decision.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM task_decisions WHERE id = ?", decision.getId()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void rejectsSilentReassignmentWithoutCommandHistory() {
    Fixture fixture = fixture("silent-reassign");
    TaskExecution task =
        taskExecutionRepository.saveAndFlush(
            task(fixture.nodeExecutionId(), ASSIGNEE_ID, "Protected assignment"));

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE task_executions SET assignee_id = ? WHERE id = ?",
                    UUID.randomUUID(),
                    task.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("requires append-only history");
  }

  @Test
  void storesRejectedAsCompletedOutcomeAndPreservesTerminalTask() {
    Fixture fixture = fixture("outcome");
    TaskExecution task =
        taskExecutionRepository.saveAndFlush(
            task(fixture.nodeExecutionId(), ASSIGNEE_ID, "Outcome review"));

    task.complete(BusinessOutcome.REJECTED, NOW.plusSeconds(5));
    taskExecutionRepository.saveAndFlush(task);

    TaskExecution persisted = taskExecutionRepository.findById(task.getId()).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(persisted.getOutcome()).isEqualTo("REJECTED");
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE task_executions SET status = 'REJECTED' WHERE id = ?", task.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM task_executions WHERE id = ?", task.getId()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void usesOptimisticLockingForConcurrentTaskTransitions() {
    Fixture fixture = fixture("optimistic");
    TaskExecution task =
        taskExecutionRepository.saveAndFlush(
            task(fixture.nodeExecutionId(), ASSIGNEE_ID, "Concurrent review"));
    TaskExecution firstCopy = taskExecutionRepository.findById(task.getId()).orElseThrow();
    TaskExecution staleCopy = taskExecutionRepository.findById(task.getId()).orElseThrow();

    firstCopy.claim(ASSIGNEE_ID);
    taskExecutionRepository.saveAndFlush(firstCopy);
    staleCopy.claim(ASSIGNEE_ID);

    assertThatThrownBy(() -> taskExecutionRepository.saveAndFlush(staleCopy))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  private ParticipantSnapshot participant(Fixture fixture, UUID resolvedUserId) {
    return ParticipantSnapshot.create(
        UUID.randomUUID(),
        fixture.eventId(),
        fixture.nodeExecutionId(),
        null,
        "CREATOR_MANAGER",
        "config-hash",
        objectMapper.createObjectNode().put("source", "ticket.creator"),
        ParticipantResolutionStatus.RESOLVED,
        "EMPLOYEE",
        UUID.randomUUID(),
        resolvedUserId,
        "APPROVER",
        objectMapper.createObjectNode().put("resolvedUserId", resolvedUserId.toString()),
        NOW);
  }

  private TaskExecution task(UUID nodeExecutionId, UUID assigneeId, String title) {
    return TaskExecution.create(
        UUID.randomUUID(),
        nodeExecutionId,
        null,
        assigneeId,
        title,
        "Review the submitted request",
        objectMapper.createObjectNode().put("type", "object"),
        objectMapper.createObjectNode().put("amount", 100),
        50,
        NOW.plusSeconds(3600),
        NOW);
  }

  private Fixture fixture(String label) {
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
            + "VALUES (?, ?, 'Task workflow', 'ACTIVE', ?, ?, now(), now())",
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
            + "VALUES (?, ?, 'review', 'APPROVAL', 'Review', 1, '{}'::jsonb, '{}'::jsonb)",
        nodeId,
        versionId);
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'checksum', "
            + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() "
            + "WHERE id = ?",
        ACTOR_ID,
        versionId);
    jdbcTemplate.update(
        "INSERT INTO request_types "
            + "(id, key, name, category, workflow_definition_id, active, "
            + "creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Task request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
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

    UUID eventId = UUID.randomUUID();
    Event event =
        Event.createRoot(
            eventId,
            ticketId,
            versionId,
            revisionId,
            null,
            null,
            "USER_SUBMIT",
            "task-" + suffix,
            objectMapper.createObjectNode(),
            ACTOR_ID,
            NOW);
    eventRepository.saveAndFlush(event);
    NodeExecution nodeExecution =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            nodeId,
            "task-node-" + suffix,
            UUID.randomUUID(),
            0,
            "root",
            null,
            null,
            null,
            objectMapper.createObjectNode(),
            revisionId,
            NOW);
    nodeExecutionRepository.saveAndFlush(nodeExecution);
    return new Fixture(eventId, nodeExecution.getId());
  }

  private record Fixture(UUID eventId, UUID nodeExecutionId) {}
}
