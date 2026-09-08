package com.fpt.workflow.ticket.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.engine.FieldEditability;
import com.fpt.workflow.form.engine.FieldRequirement;
import com.fpt.workflow.form.engine.FieldSemanticMetadata;
import com.fpt.workflow.form.engine.FieldValidationRules;
import com.fpt.workflow.form.engine.FieldVisibility;
import com.fpt.workflow.form.engine.FormFieldDefinition;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
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
class RequestTicketApiIT {

  private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("request_ticket_api_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private EventRepository eventRepository;

  @Test
  @WithMockActor
  void exposesCatalogCreateSchemaAndDraftCrud() throws Exception {
    Fixture fixture = fixture(List.of(field("amount", false)));

    mockMvc
        .perform(get("/api/v1/request-types"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.key == '" + fixture.requestTypeKey() + "')]").isNotEmpty());
    mockMvc
        .perform(get("/api/v1/request-types/{key}/create-schema", fixture.requestTypeKey()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.sourceWorkflowVersionId").value(fixture.publishedVersionId().toString()))
        .andExpect(jsonPath("$.formSchemaVersion").value(1))
        .andExpect(jsonPath("$.formSchemaChecksum").value(fixture.formChecksum()));

    UUID commandId = UUID.randomUUID();
    JsonNode draft =
        createDraft(fixture, commandId, objectMapper.createObjectNode().put("amount", "10"));
    UUID ticketId = UUID.fromString(draft.path("ticket").path("id").asText());
    mockMvc
        .perform(get("/api/v1/tickets/{id}", ticketId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticket.status").value("DRAFT"));
    mockMvc
        .perform(
            put("/api/v1/tickets/{id}/draft", ticketId)
                .header(TicketController.COMMAND_ID_HEADER, UUID.randomUUID())
                .header("If-Match", 99)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"dataJson\":{\"amount\":11},\"subjects\":[],\"expectedDataRevision\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));
  }

  @Test
  @WithMockActor
  void formOpenedOnOldVersionSubmitsAgainstCompatibleNewVersionAndReplaysDuplicate()
      throws Exception {
    Fixture fixture = fixture(List.of(field("amount", false)));
    JsonNode draft =
        createDraft(
            fixture, UUID.randomUUID(), objectMapper.createObjectNode().put("amount", "10"));
    UUID ticketId = UUID.fromString(draft.path("ticket").path("id").asText());
    PublishedForm newer =
        publishNext(fixture, List.of(field("amount", false), field("note", false)));
    UUID commandId = UUID.randomUUID();
    String submitBody = submitBody(fixture.publishedVersionId(), fixture.formChecksum());

    mockMvc
        .perform(
            post("/api/v1/tickets/{id}/submit", ticketId)
                .header(TicketController.COMMAND_ID_HEADER, commandId)
                .header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticket.status").value("SUBMITTED"));
    mockMvc
        .perform(
            post("/api/v1/tickets/{id}/submit", ticketId)
                .header(TicketController.COMMAND_ID_HEADER, commandId)
                .header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ticket.status").value("SUBMITTED"));

    assertThat(eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId))
        .singleElement()
        .extracting(event -> event.getWorkflowVersionId())
        .isEqualTo(newer.versionId());
  }

  @Test
  @WithMockActor
  void newRequiredFieldReturnsSchemaChangedWithoutCreatingEvent() throws Exception {
    Fixture fixture = fixture(List.of());
    JsonNode draft = createDraft(fixture, UUID.randomUUID(), objectMapper.createObjectNode());
    UUID ticketId = UUID.fromString(draft.path("ticket").path("id").asText());
    publishNext(fixture, List.of(field("reason", true)));

    mockMvc
        .perform(
            post("/api/v1/tickets/{id}/submit", ticketId)
                .header(TicketController.COMMAND_ID_HEADER, UUID.randomUUID())
                .header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(fixture.publishedVersionId(), fixture.formChecksum())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("FORM_SCHEMA_CHANGED"));

    Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
    assertThat(ticket.getStatus().name()).isEqualTo("DRAFT");
    assertThat(eventRepository.findAllByTicketIdOrderByStartedAtAsc(ticketId)).isEmpty();
  }

  private JsonNode createDraft(Fixture fixture, UUID commandId, JsonNode data) throws Exception {
    var body = objectMapper.createObjectNode();
    body.put("requestTypeId", fixture.requestTypeId().toString());
    body.set("dataJson", data);
    body.set("subjects", objectMapper.createArrayNode());
    String response =
        mockMvc
            .perform(
                post("/api/v1/tickets/drafts")
                    .header(TicketController.COMMAND_ID_HEADER, commandId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
  }

  private Fixture fixture(List<FormFieldDefinition> fields) {
    UUID definitionId = UUID.randomUUID();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions (id,key,name,lifecycle,owner_id,created_by,created_at,updated_at) VALUES (?,?,'Workflow','ACTIVE',?,?,now(),now())",
        definitionId,
        "workflow_" + suffix,
        ACTOR,
        ACTOR);
    PublishedForm published = insertAndPublish(definitionId, 1, fields);
    UUID requestTypeId = UUID.randomUUID();
    String requestTypeKey = "request_" + suffix;
    jdbcTemplate.update(
        "INSERT INTO request_types (id,key,name,category,workflow_definition_id,active,creation_policy_json,created_at,updated_at) VALUES (?,?,'Request','GENERAL',?,true,'{}'::jsonb,now(),now())",
        requestTypeId,
        requestTypeKey,
        definitionId);
    return new Fixture(
        definitionId, requestTypeId, requestTypeKey, published.versionId(), published.checksum());
  }

  private PublishedForm publishNext(Fixture fixture, List<FormFieldDefinition> fields) {
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status='SUPERSEDED' WHERE id=?",
        fixture.publishedVersionId());
    return insertAndPublish(fixture.definitionId(), 2, fields);
  }

  private PublishedForm insertAndPublish(
      UUID definitionId, int versionNo, List<FormFieldDefinition> fields) {
    UUID versionId = UUID.randomUUID();
    String checksum = "form-" + UUID.randomUUID();
    FormSchema schema = new FormSchema("ticket", WorkflowFormType.TICKET_FORM, fields);
    jdbcTemplate.update(
        "INSERT INTO workflow_versions (id,definition_id,version_no,status,created_by,created_at) VALUES (?,?,?,'DRAFT',?,now())",
        versionId,
        definitionId,
        versionNo,
        ACTOR);
    jdbcTemplate.update(
        "INSERT INTO workflow_forms (id,workflow_version_id,form_key,form_type,schema_json,schema_checksum) VALUES (?,?,'ticket','TICKET_FORM',?::jsonb,?)",
        UUID.randomUUID(),
        versionId,
        objectMapper.valueToTree(schema).toString(),
        checksum);
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status='PUBLISHED',checksum=?,execution_package_json='{}'::jsonb,published_by=?,published_at=now() WHERE id=?",
        "package-" + versionNo,
        ACTOR,
        versionId);
    jdbcTemplate.update(
        "UPDATE workflow_definitions SET current_published_version_id=? WHERE id=?",
        versionId,
        definitionId);
    return new PublishedForm(versionId, checksum);
  }

  private FormFieldDefinition field(String key, boolean required) {
    return new FormFieldDefinition(
        UUID.randomUUID(),
        key,
        key,
        null,
        null,
        0,
        TypeDescriptor.nullable(CanonicalValueType.STRING),
        null,
        false,
        required ? FieldRequirement.always() : FieldRequirement.never(),
        FieldVisibility.always(),
        FieldEditability.editable(),
        FieldValidationRules.none(),
        null,
        new FieldSemanticMetadata(false, false, false, false, false));
  }

  private String submitBody(UUID sourceVersionId, String checksum) {
    return objectMapper
        .createObjectNode()
        .put("sourceWorkflowVersionId", sourceVersionId.toString())
        .put("schemaChecksum", checksum)
        .put("changeReason", "submit")
        .put("expectedDataRevision", 0)
        .toString();
  }

  private record PublishedForm(UUID versionId, String checksum) {}

  private record Fixture(
      UUID definitionId,
      UUID requestTypeId,
      String requestTypeKey,
      UUID publishedVersionId,
      String formChecksum) {}
}
