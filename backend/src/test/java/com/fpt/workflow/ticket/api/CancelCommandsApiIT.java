package com.fpt.workflow.ticket.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
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
class CancelCommandsApiIT {

  private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID OTHER_ACTOR = UUID.fromString("20000000-0000-4000-8000-000000000002");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("cancel_commands_api_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private TaskExecutionRepository taskRepository;

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "USER")
  void cancelsTicketAndCascadesToEventAndTasks() throws Exception {
    UUID requestTypeId = setupCatalog();
    Instant now = Instant.now();
    UUID ticketId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID versionId = getVersionId(requestTypeId);

    // Create submitted ticket
    jdbcTemplate.update(
        "INSERT INTO tickets (id,request_type_id,creator_id,status,data_json,data_revision,lock_version,created_at,updated_at) VALUES (?,?,?, 'DRAFT', '{}'::jsonb, 0, 0, now(), now())",
        ticketId,
        requestTypeId,
        ACTOR);
    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id,ticket_id,revision_no,data_snapshot_json,source_schema_version,schema_checksum,submitted_by,submitted_at) VALUES (?,?,1,'{}'::jsonb,'1','ck',?,now())",
        revisionId,
        ticketId,
        ACTOR);
    jdbcTemplate.update(
        "UPDATE tickets SET status='SUBMITTED', data_revision=1, current_revision_id=?, submitted_at=now() WHERE id=?",
        revisionId,
        ticketId);

    // Create running event
    Event event =
        eventRepository.saveAndFlush(
            Event.createRoot(
                eventId,
                ticketId,
                versionId,
                revisionId,
                null,
                null,
                "SUBMIT",
                "cmd-1",
                objectMapper.createObjectNode(),
                ACTOR,
                now));
    event.markRunning();
    eventRepository.saveAndFlush(event);

    // Create node execution and task
    UUID nodeExecutionId = UUID.randomUUID();
    UUID nodeDefId = getNodeDefId(versionId);
    NodeExecution node =
        nodeExecutionRepository.saveAndFlush(
            NodeExecution.create(
                nodeExecutionId,
                eventId,
                nodeDefId,
                "act-1",
                UUID.randomUUID(),
                0,
                "root",
                null,
                null,
                null,
                objectMapper.createObjectNode(),
                revisionId,
                now));
    node.markReady();
    node.start(now);
    nodeExecutionRepository.saveAndFlush(node);

    UUID taskId = UUID.randomUUID();
    TaskExecution task =
        TaskExecution.create(
            taskId,
            nodeExecutionId,
            null,
            ACTOR,
            "Approval Task",
            "Desc",
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            10,
            now.plusSeconds(3600),
            now);
    task.claim(ACTOR);
    taskRepository.saveAndFlush(task);

    UUID commandId = UUID.randomUUID();

    // Cancel ticket via POST /api/v1/tickets/{id}/cancel
    mockMvc
        .perform(
            post("/api/v1/tickets/{id}/cancel", ticketId)
                .header(TicketController.COMMAND_ID_HEADER, commandId)
                .header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"No longer needed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticket.status").value("CANCELLED"));

    // Verify ticket is CANCELLED in DB
    Ticket updatedTicket = ticketRepository.findById(ticketId).orElseThrow();
    assertThat(updatedTicket.getStatus()).isEqualTo(TicketStatus.CANCELLED);
    assertThat(updatedTicket.getCompletedAt()).isNotNull();

    // Verify event is CANCELLED
    Event updatedEvent = eventRepository.findById(eventId).orElseThrow();
    assertThat(updatedEvent.getStatus()).isEqualTo(EventStatus.CANCELLED);

    // Verify node execution is CANCELLED
    NodeExecution updatedNode = nodeExecutionRepository.findById(nodeExecutionId).orElseThrow();
    assertThat(updatedNode.getStatus()).isEqualTo(NodeExecutionStatus.CANCELLED);

    // Verify task is CANCELLED
    TaskExecution updatedTask = taskRepository.findById(taskId).orElseThrow();
    assertThat(updatedTask.getStatus()).isEqualTo(TaskStatus.CANCELLED);

    // Duplicate call with same commandId is idempotent
    mockMvc
        .perform(
            post("/api/v1/tickets/{id}/cancel", ticketId)
                .header(TicketController.COMMAND_ID_HEADER, commandId)
                .header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"No longer needed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticket.status").value("CANCELLED"));
  }

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "ADMIN")
  void cancelsEventViaOperationalEndpointAndSyncsTicket() throws Exception {
    UUID requestTypeId = setupCatalog();
    Instant now = Instant.now();
    UUID ticketId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID versionId = getVersionId(requestTypeId);

    jdbcTemplate.update(
        "INSERT INTO tickets (id,request_type_id,creator_id,status,data_json,data_revision,lock_version,created_at,updated_at) VALUES (?,?,?, 'DRAFT', '{}'::jsonb, 0, 0, now(), now())",
        ticketId,
        requestTypeId,
        ACTOR);
    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id,ticket_id,revision_no,data_snapshot_json,source_schema_version,schema_checksum,submitted_by,submitted_at) VALUES (?,?,1,'{}'::jsonb,'1','ck',?,now())",
        revisionId,
        ticketId,
        ACTOR);
    jdbcTemplate.update(
        "UPDATE tickets SET status='SUBMITTED', data_revision=1, current_revision_id=?, submitted_at=now() WHERE id=?",
        revisionId,
        ticketId);

    Event event =
        eventRepository.saveAndFlush(
            Event.createRoot(
                eventId,
                ticketId,
                versionId,
                revisionId,
                null,
                null,
                "SUBMIT",
                "cmd-2",
                objectMapper.createObjectNode(),
                ACTOR,
                now));
    event.markRunning();
    Event runningEvent = eventRepository.saveAndFlush(event);

    UUID commandId = UUID.randomUUID();

    // Cancel event via POST /api/v1/events/{id}/cancel
    mockMvc
        .perform(
            post("/api/v1/events/{id}/cancel", eventId)
                .header("If-Match", runningEvent.getLockVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"commandId\":\""
                        + commandId
                        + "\",\"reason\":\"Admin stopped the workflow\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultJson.status").value("CANCELLED"));

    // Verify event is CANCELLED
    Event updatedEvent = eventRepository.findById(eventId).orElseThrow();
    assertThat(updatedEvent.getStatus()).isEqualTo(EventStatus.CANCELLED);

    // Verify root Ticket is synchronized to CANCELLED
    Ticket updatedTicket = ticketRepository.findById(ticketId).orElseThrow();
    assertThat(updatedTicket.getStatus()).isEqualTo(TicketStatus.CANCELLED);
    assertThat(updatedTicket.getCompletedAt()).isNotNull();
  }

  private UUID setupCatalog() {
    UUID defId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID reqTypeId = UUID.randomUUID();
    UUID nodeDefId = UUID.randomUUID();

    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id,key,name,lifecycle,owner_id,created_by,created_at,updated_at) VALUES (?,?,'Wf','ACTIVE',?,?,now(),now())",
        defId,
        "wf_" + UUID.randomUUID().toString().replace("-", ""),
        ACTOR,
        ACTOR);
    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id,definition_id,version_no,status,created_by,created_at) VALUES (?,?,1,'DRAFT',?,now())",
        versionId,
        defId,
        ACTOR);
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id,workflow_version_id,node_key,node_type,name,description,config_schema_version,config_json) VALUES (?,?,'approval','APPROVAL','Review','Desc',1,'{}'::jsonb)",
        nodeDefId,
        versionId);
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status='PUBLISHED', checksum='ck-1', execution_package_json='{}'::jsonb, published_by=?, published_at=now() WHERE id=?",
        ACTOR,
        versionId);
    jdbcTemplate.update(
        "INSERT INTO request_types (id,key,name,category,workflow_definition_id,active,creation_policy_json,created_at,updated_at) VALUES (?,?,'Req','GENERAL',?,true,'{}'::jsonb,now(),now())",
        reqTypeId,
        "req_" + UUID.randomUUID().toString().replace("-", ""),
        defId);
    return reqTypeId;
  }

  private UUID getVersionId(UUID requestTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT v.id FROM workflow_versions v JOIN request_types rt ON rt.workflow_definition_id = v.definition_id WHERE rt.id = ? AND v.status = 'PUBLISHED'",
        UUID.class,
        requestTypeId);
  }

  private UUID getNodeDefId(UUID versionId) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM workflow_nodes WHERE workflow_version_id = ? LIMIT 1",
        UUID.class,
        versionId);
  }
}
