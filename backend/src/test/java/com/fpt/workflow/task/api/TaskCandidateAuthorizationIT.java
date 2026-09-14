package com.fpt.workflow.task.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.task.domain.TaskCandidate;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskCandidateRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.UUID;
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
class TaskCandidateAuthorizationIT {

  private static final UUID CREATOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID CANDIDATE_USER_ID =
      UUID.fromString("20000000-0000-4000-8000-000000000002");
  private static final UUID UNAUTHORIZED_USER_ID =
      UUID.fromString("30000000-0000-4000-8000-000000000003");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("task_candidate_auth_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private MockMvc mockMvc;
  @Autowired private TaskExecutionRepository taskRepository;
  @Autowired private TaskCandidateRepository candidateRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void enforcesCandidateAuthorizationAndSupportsUnclaim() throws Exception {
    UUID definitionId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID nodeDefId = UUID.randomUUID();
    UUID nodeExecutionId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    Instant now = Instant.now();

    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id,key,name,lifecycle,owner_id,created_by,created_at,updated_at) VALUES (?,?,'Workflow','ACTIVE',?,?,now(),now())",
        definitionId,
        "wf_" + UUID.randomUUID().toString().replace("-", ""),
        CREATOR_ID,
        CREATOR_ID);
    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id,definition_id,version_no,status,created_by,created_at) VALUES (?,?,1,'DRAFT',?,now())",
        versionId,
        definitionId,
        CREATOR_ID);
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id,workflow_version_id,node_key,node_type,name,description,config_schema_version,config_json) VALUES (?,?,'approval','APPROVAL','Review','Desc',1,'{}'::jsonb)",
        nodeDefId,
        versionId);
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status='PUBLISHED', checksum='ck-1', execution_package_json='{}'::jsonb, published_by=?, published_at=now() WHERE id=?",
        CREATOR_ID,
        versionId);
    UUID requestTypeId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO request_types (id,key,name,category,workflow_definition_id,active,creation_policy_json,created_at,updated_at) VALUES (?,?,'Request','GENERAL',?,true,'{}'::jsonb,now(),now())",
        requestTypeId,
        "req_" + UUID.randomUUID().toString().replace("-", ""),
        definitionId);
    jdbcTemplate.update(
        "INSERT INTO tickets (id,request_type_id,creator_id,status,data_json,data_revision,lock_version,created_at,updated_at) VALUES (?,?,?, 'DRAFT', '{}'::jsonb, 0, 0, now(), now())",
        ticketId,
        requestTypeId,
        CREATOR_ID);
    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id,ticket_id,revision_no,data_snapshot_json,source_schema_version,schema_checksum,submitted_by,submitted_at) VALUES (?,?,1,'{}'::jsonb,'1','ck',?,now())",
        revisionId,
        ticketId,
        CREATOR_ID);
    jdbcTemplate.update(
        "UPDATE tickets SET status='SUBMITTED', data_revision=1, current_revision_id=?, submitted_at=now() WHERE id=?",
        revisionId,
        ticketId);

    eventRepository.saveAndFlush(
        Event.createRoot(
            eventId,
            ticketId,
            versionId,
            revisionId,
            null,
            null,
            "TEST",
            "key",
            objectMapper.createObjectNode(),
            CREATOR_ID,
            now));

    nodeExecutionRepository.saveAndFlush(
        NodeExecution.create(
            nodeExecutionId,
            eventId,
            nodeDefId,
            "activation-1",
            UUID.randomUUID(),
            0,
            "root",
            null,
            null,
            null,
            objectMapper.createObjectNode(),
            revisionId,
            now));

    TaskExecution task =
        TaskExecution.create(
            taskId,
            nodeExecutionId,
            null,
            null,
            "Pool Task",
            "Review pool item",
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            50,
            now.plusSeconds(3600),
            now);
    taskRepository.saveAndFlush(task);

    // Add candidate: only CANDIDATE_USER_ID is eligible
    candidateRepository.saveAndFlush(
        TaskCandidate.create(
            taskId, CANDIDATE_USER_ID, "USER", objectMapper.createObjectNode(), now));
    candidateRepository.saveAndFlush(
        TaskCandidate.create(
            taskId,
            UNAUTHORIZED_USER_ID,
            "SEQUENTIAL",
            objectMapper.createObjectNode().put("orderIndex", 1),
            now));

    // Claimable-pool candidates can discover the task; a future sequential assignee cannot.
    mockMvc
        .perform(
            get("/api/v1/tasks")
                .header("X-Actor-Id", CANDIDATE_USER_ID.toString())
                .header("X-Actor-Roles", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + taskId + "')]").isNotEmpty());

    mockMvc
        .perform(
            get("/api/v1/tasks")
                .header("X-Actor-Id", UNAUTHORIZED_USER_ID.toString())
                .header("X-Actor-Roles", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + taskId + "')]").isEmpty());

    // 1. Unauthorized actor attempt to claim -> 403 Forbidden
    mockMvc
        .perform(
            post("/api/v1/tasks/{taskId}/claim", taskId)
                .header(TaskController.COMMAND_ID_HEADER, UUID.randomUUID())
                .header("If-Match", 0)
                .header("X-Actor-Id", UNAUTHORIZED_USER_ID.toString())
                .header("X-Actor-Roles", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"I want to claim\"}"))
        .andExpect(status().isForbidden());

    // 2. Authorized candidate claims -> 200 OK
    mockMvc
        .perform(
            post("/api/v1/tasks/{taskId}/claim", taskId)
                .header(TaskController.COMMAND_ID_HEADER, UUID.randomUUID())
                .header("If-Match", 0)
                .header("X-Actor-Id", CANDIDATE_USER_ID.toString())
                .header("X-Actor-Roles", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"I will take this task\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLAIMED"))
        .andExpect(jsonPath("$.assigneeId").value(CANDIDATE_USER_ID.toString()));

    // 3. Unauthorized actor tries to unclaim -> 403 Forbidden
    mockMvc
        .perform(
            post("/api/v1/tasks/{taskId}/unclaim", taskId)
                .header(TaskController.COMMAND_ID_HEADER, UUID.randomUUID())
                .header("If-Match", 1)
                .header("X-Actor-Id", UNAUTHORIZED_USER_ID.toString())
                .header("X-Actor-Roles", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());

    // 4. Assignee unclaims -> 200 OK, returns to READY status
    mockMvc
        .perform(
            post("/api/v1/tasks/{taskId}/unclaim", taskId)
                .header(TaskController.COMMAND_ID_HEADER, UUID.randomUUID())
                .header("If-Match", 1)
                .header("X-Actor-Id", CANDIDATE_USER_ID.toString())
                .header("X-Actor-Roles", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"));

    TaskExecution afterUnclaim = taskRepository.findById(taskId).orElseThrow();
    assertThat(afterUnclaim.getStatus()).isEqualTo(TaskStatus.READY);
    assertThat(afterUnclaim.getAssigneeId()).isNull();
  }
}
