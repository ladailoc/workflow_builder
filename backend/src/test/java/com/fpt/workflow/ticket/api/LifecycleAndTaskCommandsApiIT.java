package com.fpt.workflow.ticket.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.operations.job.WorkflowJobRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.engine.FieldEditability;
import com.fpt.workflow.form.engine.FieldRequirement;
import com.fpt.workflow.form.engine.FieldSemanticMetadata;
import com.fpt.workflow.form.engine.FieldValidationRules;
import com.fpt.workflow.form.engine.FieldVisibility;
import com.fpt.workflow.form.engine.FormFieldDefinition;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskAssignmentHistoryRepository;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.List;
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
public class LifecycleAndTaskCommandsApiIT {

  private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID OTHER_USER = UUID.fromString("20000000-0000-4000-8000-000000000002");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("lifecycle_commands_api_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private NodeExecutionRepository nodeExecutionRepository;
  @Autowired private TaskExecutionRepository taskRepository;
  @Autowired private TaskAssignmentHistoryRepository assignmentHistoryRepository;
  @Autowired private IntegrationExecutionRepository integrationRepository;
  @Autowired private WorkflowJobRepository jobRepository;
  @Autowired private AuditEventRepository auditRepository;

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "OPERATOR")
  void cancelEvent_operator_cancelsEvent() throws Exception {
    UUID requestTypeId = setupCatalog();
    UUID versionId = getVersionId(requestTypeId);
    UUID ticketId = createSubmittedTicket(requestTypeId, ACTOR);
    UUID eventId = UUID.randomUUID();

    Event event =
        eventRepository.saveAndFlush(
            Event.createRoot(
                eventId,
                ticketId,
                versionId,
                getTicketRevisionId(ticketId),
                null,
                null,
                "SUBMIT",
                "cmd-cancel-1",
                objectMapper.createObjectNode(),
                ACTOR,
                Instant.now()));
    event.markRunning();
    Event runningEvent = eventRepository.saveAndFlush(event);

    UUID commandId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/events/{id}/cancel", eventId)
                .header("If-Match", runningEvent.getLockVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandId\":\"" + commandId + "\",\"reason\":\"Admin cancel\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultJson.status").value("CANCELLED"));

    Event updated = eventRepository.findById(eventId).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(EventStatus.CANCELLED);
  }

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "OPERATOR")
  void restartEvent_operator_createsNewEventAndEnqueuesJob() throws Exception {
    UUID requestTypeId = setupCatalog();
    UUID versionId = getVersionId(requestTypeId);
    UUID ticketId = createSubmittedTicket(requestTypeId, ACTOR);
    UUID eventId = UUID.randomUUID();

    Event event =
        eventRepository.saveAndFlush(
            Event.createRoot(
                eventId,
                ticketId,
                versionId,
                getTicketRevisionId(ticketId),
                null,
                null,
                "SUBMIT",
                "cmd-restart-1",
                objectMapper.createObjectNode(),
                ACTOR,
                Instant.now()));
    event.markRunning();
    event.cancel("OPERATOR_CANCELLED", Instant.now());
    Event cancelled = eventRepository.saveAndFlush(event);

    UUID commandId = UUID.randomUUID();
    var resultActions =
        mockMvc
            .perform(
                post("/api/v1/events/{id}/restart", eventId)
                    .header("If-Match", cancelled.getLockVersion())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"commandId\":\"" + commandId + "\",\"reason\":\"Operator restart\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultJson.status").value("CREATED"));

    String respStr = resultActions.andReturn().getResponse().getContentAsString();
    JsonNode respNode = objectMapper.readTree(respStr);
    UUID newEventId = UUID.fromString(respNode.path("resultJson").path("newEventId").asText());

    Event newEvent = eventRepository.findById(newEventId).orElseThrow();
    assertThat(newEvent.getPreviousEventId()).isEqualTo(eventId);
    assertThat(newEvent.getRestartedFromEventId()).isEqualTo(eventId);
    assertThat(newEvent.getStatus()).isEqualTo(EventStatus.CREATED);

    // Verify EVENT_START job is present
    boolean jobExists =
        jobRepository.findAll().stream()
            .anyMatch(j -> "EVENT_START".equals(j.getJobType()) && newEventId.equals(j.getAggregateId()));
    assertThat(jobExists).isTrue();
  }

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "USER")
  void reopenTicket_terminalTicket_reopensAndEnqueuesEventStart() throws Exception {
    UUID requestTypeId = setupCatalog();
    UUID versionId = getVersionId(requestTypeId);
    UUID ticketId = createSubmittedTicket(requestTypeId, ACTOR);
    UUID eventId = UUID.randomUUID();

    Event event =
        eventRepository.saveAndFlush(
            Event.createRoot(
                eventId,
                ticketId,
                versionId,
                getTicketRevisionId(ticketId),
                null,
                null,
                "SUBMIT",
                "cmd-reopen-1",
                objectMapper.createObjectNode(),
                ACTOR,
                Instant.now()));
    event.markRunning();
    event.cancel("Cancelled", Instant.now());
    eventRepository.saveAndFlush(event);

    // Mark ticket cancelled
    Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
    ticket.cancel(Instant.now());
    ticket = ticketRepository.saveAndFlush(ticket);

    UUID commandId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/tickets/{id}/reopen", ticketId)
                .header("X-Command-Id", commandId.toString())
                .header("If-Match", ticket.getLockVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Customer requested reopening\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticket.status").value("SUBMITTED"));

    Ticket reopened = ticketRepository.findById(ticketId).orElseThrow();
    assertThat(reopened.getStatus()).isEqualTo(TicketStatus.SUBMITTED);
    assertThat(reopened.getCompletedAt()).isNull();

    // Verify a new Event exists with triggerType TICKET_REOPEN
    List<Event> events = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId);
    assertThat(events).hasSize(2);
    Event reopenedEvent = events.get(1);
    assertThat(reopenedEvent.getTriggerType()).isEqualTo("TICKET_REOPEN");
    assertThat(reopenedEvent.getPreviousEventId()).isEqualTo(eventId);

    boolean jobExists =
        jobRepository.findAll().stream()
            .anyMatch(
                j ->
                    "EVENT_START".equals(j.getJobType())
                        && reopenedEvent.getId().equals(j.getAggregateId()));
    assertThat(jobExists).isTrue();
  }

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "USER")
  void resubmitTicket_terminalTicket_createsRevisionAndNewEvent() throws Exception {
    UUID requestTypeId = setupCatalog();
    UUID versionId = getVersionId(requestTypeId);
    UUID ticketId = createSubmittedTicket(requestTypeId, ACTOR);
    UUID eventId = UUID.randomUUID();

    Event event =
        eventRepository.saveAndFlush(
            Event.createRoot(
                eventId,
                ticketId,
                versionId,
                getTicketRevisionId(ticketId),
                null,
                null,
                "SUBMIT",
                "cmd-resubmit-1",
                objectMapper.createObjectNode(),
                ACTOR,
                Instant.now()));
    event.markRunning();
    event.complete("REJECTED", Instant.now());
    eventRepository.saveAndFlush(event);

    // Mark ticket rejected
    Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
    ticket.reject(Instant.now());
    ticket = ticketRepository.saveAndFlush(ticket);

    UUID commandId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/tickets/{id}/resubmit", ticketId)
                .header("X-Command-Id", commandId.toString())
                .header("If-Match", ticket.getLockVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"dataJson\":{\"amount\":500},\"changeReason\":\"Corrected invoice amount\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticket.status").value("SUBMITTED"))
        .andExpect(jsonPath("$.ticket.dataRevision").value(2));

    Ticket resubmitted = ticketRepository.findById(ticketId).orElseThrow();
    assertThat(resubmitted.getStatus()).isEqualTo(TicketStatus.SUBMITTED);
    assertThat(resubmitted.getDataRevision()).isEqualTo(2);

    List<Event> events = eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId);
    assertThat(events).hasSize(2);
    Event resubmittedEvent = events.get(1);
    assertThat(resubmittedEvent.getTriggerType()).isEqualTo("TICKET_RESUBMIT");
  }

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "USER")
  void unclaimTask_returnsToReadyAndClearsAssignee() throws Exception {
    UUID requestTypeId = setupCatalog();
    UUID versionId = getVersionId(requestTypeId);
    UUID ticketId = createSubmittedTicket(requestTypeId, ACTOR);
    UUID eventId = UUID.randomUUID();

    Event event =
        eventRepository.saveAndFlush(
            Event.createRoot(
                eventId,
                ticketId,
                versionId,
                getTicketRevisionId(ticketId),
                null,
                null,
                "SUBMIT",
                "cmd-unclaim-1",
                objectMapper.createObjectNode(),
                ACTOR,
                Instant.now()));

    UUID nodeDefId = getNodeDefId(versionId, "approval");
    NodeExecution node = createNodeExecution(eventId, nodeDefId, getTicketRevisionId(ticketId));

    UUID taskId = UUID.randomUUID();
    TaskExecution task =
        TaskExecution.create(
            taskId,
            node.getId(),
            null,
            ACTOR,
            "Approval Task",
            "Approve document",
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            50,
            Instant.now().plusSeconds(3600),
            Instant.now());
    task.claim(ACTOR);
    taskRepository.saveAndFlush(task);

    UUID commandId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/tasks/{taskId}/unclaim", taskId)
                .header("X-Command-Id", commandId.toString())
                .header("If-Match", task.getLockVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Unclaiming task\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.assigneeId").doesNotExist());

    TaskExecution updated = taskRepository.findById(taskId).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(TaskStatus.READY);
    assertThat(updated.getAssigneeId()).isNull();

    boolean historyLogged =
        assignmentHistoryRepository.findAll().stream()
            .anyMatch(h -> taskId.equals(h.getTaskId()) && "UNCLAIM".equals(h.getActionType().name()));
    assertThat(historyLogged).isTrue();
  }

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "OPERATOR")
  void forceCompleteTask_operator_completesTaskAndRoutes() throws Exception {
    UUID requestTypeId = setupCatalog();
    UUID versionId = getVersionId(requestTypeId);
    UUID ticketId = createSubmittedTicket(requestTypeId, ACTOR);
    UUID eventId = UUID.randomUUID();

    Event event =
        eventRepository.saveAndFlush(
            Event.createRoot(
                eventId,
                ticketId,
                versionId,
                getTicketRevisionId(ticketId),
                null,
                null,
                "SUBMIT",
                "cmd-force-1",
                objectMapper.createObjectNode(),
                ACTOR,
                Instant.now()));

    UUID nodeDefId = getNodeDefId(versionId, "approval");
    NodeExecution node = createNodeExecution(eventId, nodeDefId, getTicketRevisionId(ticketId));
    node.start(Instant.now());
    nodeExecutionRepository.saveAndFlush(node);

    UUID taskId = UUID.randomUUID();
    TaskExecution task =
        TaskExecution.create(
            taskId,
            node.getId(),
            null,
            OTHER_USER,
            "Manager Review",
            "Review",
            objectMapper.createObjectNode(),
            objectMapper.createObjectNode(),
            50,
            Instant.now().plusSeconds(3600),
            Instant.now());
    task.claim(OTHER_USER);
    taskRepository.saveAndFlush(task);

    UUID commandId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/tasks/{taskId}/force-complete", taskId)
                .header("X-Command-Id", commandId.toString())
                .header("If-Match", task.getLockVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Emergency operator override\",\"outcome\":\"APPROVED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.outcome").value("APPROVED"));

    TaskExecution updatedTask = taskRepository.findById(taskId).orElseThrow();
    assertThat(updatedTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(updatedTask.getOutcome()).isEqualTo("APPROVED");

    NodeExecution updatedNode = nodeExecutionRepository.findById(node.getId()).orElseThrow();
    assertThat(updatedNode.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);

    boolean auditRecorded =
        auditRepository.findAll().stream()
            .anyMatch(a -> "TASK_FORCE_COMPLETED".equals(a.getEventType()));
    assertThat(auditRecorded).isTrue();
  }

  @Test
  @WithMockActor(actorId = "20000000-0000-4000-8000-000000000002", roles = "USER")
  void forceCompleteTask_regularUser_isForbidden() throws Exception {
    UUID taskId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/tasks/{taskId}/force-complete", taskId)
                .header("X-Command-Id", UUID.randomUUID())
                .header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Unauthorized\",\"outcome\":\"APPROVED\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "OPERATOR")
  void resumeExternal_operator_resumesWaitingCallback() throws Exception {
    UUID requestTypeId = setupCatalog();
    UUID versionId = getVersionId(requestTypeId);
    UUID ticketId = createSubmittedTicket(requestTypeId, ACTOR);
    UUID eventId = UUID.randomUUID();

    Event event =
        eventRepository.saveAndFlush(
            Event.createRoot(
                eventId,
                ticketId,
                versionId,
                getTicketRevisionId(ticketId),
                null,
                null,
                "SUBMIT",
                "cmd-resume-1",
                objectMapper.createObjectNode(),
                ACTOR,
                Instant.now()));
    event.markRunning();
    eventRepository.saveAndFlush(event);

    UUID systemNodeDefId = getNodeDefId(versionId, "connector");
    NodeExecution node = createNodeExecution(eventId, systemNodeDefId, getTicketRevisionId(ticketId));
    node.start(Instant.now());
    node.waitFor(RuntimeWaitReason.EXTERNAL_CALLBACK);
    node = nodeExecutionRepository.saveAndFlush(node);

    UUID actionVersionId = UUID.randomUUID();
    UUID connId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO connector_definitions (id,key,name,connector_type,handler_key,status,config_json,created_at,updated_at) VALUES (?,'erp_conn','ERP','ERP','erp_handler','ACTIVE','{}'::jsonb,now(),now())",
        connId);
    UUID actionDefId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO connector_actions (id,connector_id,action_key,name,status,created_at,updated_at) VALUES (?,?,'create_po','Create PO','ACTIVE',now(),now())",
        actionDefId,
        connId);
    jdbcTemplate.update(
        "INSERT INTO connector_action_versions (id,connector_action_id,version_no,status,input_schema_json,output_schema_json,execution_config_json,created_at) VALUES (?,?,1,'PUBLISHED','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,now())",
        actionVersionId,
        actionDefId);

    UUID integrationId = UUID.randomUUID();
    IntegrationExecution integration =
        IntegrationExecution.createRunning(
            integrationId,
            eventId,
            node.getId(),
            "erp_conn",
            "create_po",
            1,
            actionVersionId,
            "ERP:create_po",
            "idem-" + UUID.randomUUID(),
            "{}",
            Instant.now());
    integration.markWaitingCallback("corr-" + UUID.randomUUID(), Instant.now());
    integration = integrationRepository.saveAndFlush(integration);

    UUID commandId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/api/v1/integrations/{id}/resume", integrationId)
                .header("If-Match", integration.getLockVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"commandId\":\""
                        + commandId
                        + "\",\"reason\":\"Manual callback bypass\",\"outcomePort\":\"SUCCESS\",\"output\":{\"poId\":\"12345\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultJson.status").value("RESUMED"));

    IntegrationExecution updatedIntegration =
        integrationRepository.findById(integrationId).orElseThrow();
    assertThat(updatedIntegration.getStatus().name()).isEqualTo("SUCCEEDED");

    NodeExecution updatedNode = nodeExecutionRepository.findById(node.getId()).orElseThrow();
    assertThat(updatedNode.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);

    boolean auditRecorded =
        auditRepository.findAll().stream()
            .anyMatch(a -> "RESUME_EXTERNAL".equals(a.getEventType()));
    assertThat(auditRecorded).isTrue();
  }

  private NodeExecution createNodeExecution(UUID eventId, UUID nodeDefId, UUID revisionId) {
    NodeExecution node =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            nodeDefId,
            "act-" + UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            "main",
            null,
            null,
            null,
            objectMapper.createObjectNode(),
            revisionId,
            Instant.now());
    node.markReady();
    return nodeExecutionRepository.saveAndFlush(node);
  }

  private UUID setupCatalog() {
    UUID defId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID reqTypeId = UUID.randomUUID();
    UUID nodeDefId = UUID.randomUUID();
    UUID systemNodeDefId = UUID.randomUUID();

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
        "INSERT INTO workflow_nodes (id,workflow_version_id,node_key,node_type,name,description,config_schema_version,config_json) VALUES (?,?,'connector','SYSTEM_ACTION','ERP Call','Desc',1,'{}'::jsonb)",
        systemNodeDefId,
        versionId);
    UUID endNodeDefId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workflow_nodes (id,workflow_version_id,node_key,node_type,name,description,config_schema_version,config_json) VALUES (?,?,'end','END','End','Desc',1,'{}'::jsonb)",
        endNodeDefId,
        versionId);
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id,workflow_version_id,source_node_id,source_port,target_node_id,transition_type,priority,is_default) VALUES (?,?,?,?,?,'NORMAL',0,true)",
        UUID.randomUUID(),
        versionId,
        nodeDefId,
        "APPROVED",
        systemNodeDefId);
    jdbcTemplate.update(
        "INSERT INTO workflow_edges (id,workflow_version_id,source_node_id,source_port,target_node_id,transition_type,priority,is_default) VALUES (?,?,?,?,?,'NORMAL',0,true)",
        UUID.randomUUID(),
        versionId,
        systemNodeDefId,
        "SUCCESS",
        endNodeDefId);
    FormSchema schema =
        new FormSchema("ticket", WorkflowFormType.TICKET_FORM, List.of(field("amount", false)));
    jdbcTemplate.update(
        "INSERT INTO workflow_forms (id,workflow_version_id,form_key,form_type,schema_json,schema_checksum) VALUES (?,?,'ticket','TICKET_FORM',?::jsonb,'ck-1')",
        UUID.randomUUID(),
        versionId,
        objectMapper.valueToTree(schema).toString());
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status='PUBLISHED', checksum='ck-1', execution_package_json='{}'::jsonb, published_by=?, published_at=now() WHERE id=?",
        ACTOR,
        versionId);
    jdbcTemplate.update(
        "UPDATE workflow_definitions SET current_published_version_id=? WHERE id=?",
        versionId,
        defId);
    jdbcTemplate.update(
        "INSERT INTO request_types (id,key,name,category,workflow_definition_id,active,creation_policy_json,created_at,updated_at) VALUES (?,?,'Req','GENERAL',?,true,'{}'::jsonb,now(),now())",
        reqTypeId,
        "req_" + UUID.randomUUID().toString().replace("-", ""),
        defId);
    return reqTypeId;
  }

  private UUID createSubmittedTicket(UUID requestTypeId, UUID creatorId) {
    UUID ticketId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tickets (id,request_type_id,creator_id,status,data_json,data_revision,lock_version,created_at,updated_at) VALUES (?,?,?, 'DRAFT', '{}'::jsonb, 0, 0, now(), now())",
        ticketId,
        requestTypeId,
        creatorId);
    jdbcTemplate.update(
        "INSERT INTO ticket_revisions (id,ticket_id,revision_no,data_snapshot_json,source_schema_version,schema_checksum,submitted_by,submitted_at) VALUES (?,?,1,'{}'::jsonb,'1','ck',?,now())",
        revisionId,
        ticketId,
        creatorId);
    jdbcTemplate.update(
        "UPDATE tickets SET status='SUBMITTED', data_revision=1, current_revision_id=?, submitted_at=now() WHERE id=?",
        revisionId,
        ticketId);
    return ticketId;
  }

  private UUID getTicketRevisionId(UUID ticketId) {
    return jdbcTemplate.queryForObject(
        "SELECT current_revision_id FROM tickets WHERE id = ?", UUID.class, ticketId);
  }

  private UUID getVersionId(UUID requestTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT v.id FROM workflow_versions v JOIN request_types rt ON rt.workflow_definition_id = v.definition_id WHERE rt.id = ? AND v.status = 'PUBLISHED'",
        UUID.class,
        requestTypeId);
  }

  private UUID getNodeDefId(UUID versionId, String nodeKey) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM workflow_nodes WHERE workflow_version_id = ? AND node_key = ? LIMIT 1",
        UUID.class,
        versionId,
        nodeKey);
  }

  private FormFieldDefinition field(String key, boolean required) {
    return new FormFieldDefinition(
        UUID.randomUUID(),
        key,
        key,
        null,
        null,
        0,
        TypeDescriptor.nullable(CanonicalValueType.INTEGER),
        null,
        false,
        required ? FieldRequirement.always() : FieldRequirement.never(),
        FieldVisibility.always(),
        FieldEditability.editable(),
        FieldValidationRules.none(),
        null,
        new FieldSemanticMetadata(false, false, false, false, false));
  }
}
