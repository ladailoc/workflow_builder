package com.fpt.workflow.definition.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class WorkflowManagementApiIT {

  private static final UUID OWNER = UUID.fromString("20000000-0000-4000-8000-000000000002");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_management_api_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AuditEventRepository auditRepository;

  @Test
  void managesDefinitionDraftGraphFormPublishAndRequestTypeWithoutCreatingRuntime()
      throws Exception {
    String key = "MANAGED_" + UUID.randomUUID().toString().replace("-", "");
    JsonNode definition =
        body(
            mockMvc
                .perform(
                    owner(
                        post("/api/v1/workflows")
                            .header("X-Command-Id", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    objectMapper
                                        .createObjectNode()
                                        .put("key", key)
                                        .put("name", "Managed workflow")
                                        .put("description", "API fixture")
                                        .put("ownerId", OWNER.toString())))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentPublishedVersionId").doesNotExist())
                .andReturn());
    UUID workflowId = UUID.fromString(definition.path("id").asText());

    JsonNode draft =
        body(
            mockMvc
                .perform(
                    owner(
                        post("/api/v1/workflows/{id}/draft", workflowId)
                            .header("X-Command-Id", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn());
    UUID versionId = UUID.fromString(draft.path("id").asText());

    var graphDocument = objectMapper.createObjectNode();
    graphDocument.set(
        "nodes",
        objectMapper
            .createArrayNode()
            .add(node("start", "start", "START", "Start"))
            .add(node("end", "end", "END", "End")));
    graphDocument.set(
        "edges",
        objectMapper
            .createArrayNode()
            .add(
                objectMapper
                    .createObjectNode()
                    .put("clientRef", "start-end")
                    .put("sourceClientRef", "start")
                    .put("sourcePort", "STARTED")
                    .put("targetClientRef", "end")
                    .put("priority", 0)
                    .put("defaultTransition", false)
                    .put("transitionType", "NORMAL")
                    .set("configJson", objectMapper.createObjectNode())));
    var graph = objectMapper.createObjectNode().put("expectedRevision", 0);
    graph.set("graph", graphDocument);
    JsonNode graphResult =
        body(
            mockMvc
                .perform(
                    owner(
                        put(
                                "/api/v1/workflows/{id}/versions/{versionId}/graph",
                                workflowId,
                                versionId)
                            .header("X-Command-Id", UUID.randomUUID())
                            .header("If-Match", draft.path("lockVersion").asLong())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(graph))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andReturn());

    JsonNode formPayload =
        objectMapper
            .createObjectNode()
            .put("expectedRevision", graphResult.path("draft").path("revision").asLong())
            .set(
                "schemaJson",
                objectMapper
                    .createObjectNode()
                    .put("formKey", "ticket")
                    .put("formType", "TICKET_FORM")
                    .set("fields", objectMapper.createArrayNode()));
    JsonNode formResult =
        body(
            mockMvc
                .perform(
                    owner(
                        put(
                                "/api/v1/workflows/{id}/versions/{versionId}/ticket-form",
                                workflowId,
                                versionId)
                            .header("X-Command-Id", UUID.randomUUID())
                            .header(
                                "If-Match", graphResult.path("draft").path("lockVersion").asLong())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(formPayload))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.formType").value("TICKET_FORM"))
                .andReturn());

    mockMvc
        .perform(
            owner(
                post(
                    "/api/v1/workflows/{id}/versions/{versionId}/validate", workflowId, versionId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishable").value(true));

    mockMvc
        .perform(
            owner(
                get(
                    "/api/v1/workflows/{id}/versions/{versionId}/validation", workflowId, versionId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishable").value(true))
        .andExpect(jsonPath("$.workflowVersionId").value(versionId.toString()))
        .andExpect(jsonPath("$.issues").isArray());

    mockMvc
        .perform(
            owner(
                get(
                    "/api/v1/workflows/{id}/versions/{versionId}/field-dependencies",
                    workflowId,
                    versionId)
                    .param("fieldKey", "amount")
                    .param("kind", "DELETE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.change.fieldKey").value("amount"))
        .andExpect(jsonPath("$.dependencies").isArray());

    long draftLock = formResult.path("draft").path("lockVersion").asLong();
    long revision = formResult.path("draft").path("revision").asLong();
    mockMvc
        .perform(
            owner(
                post("/api/v1/workflows/{id}/versions/{versionId}/publish", workflowId, versionId)
                    .header("X-Command-Id", UUID.randomUUID())
                    .header("If-Match", draftLock)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedRevision\":" + revision + "}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));

    JsonNode detail =
        body(
            mockMvc
                .perform(owner(get("/api/v1/workflows/{id}", workflowId)))
                .andExpect(status().isOk())
                .andExpect(
                    jsonPath("$.workflow.currentPublishedVersionId").value(versionId.toString()))
                .andExpect(jsonPath("$.versions[0].status").value("PUBLISHED"))
                .andReturn());

    String requestKey = "REQUEST_" + UUID.randomUUID().toString().replace("-", "");
    JsonNode requestType =
        body(
            mockMvc
                .perform(
                    owner(
                        post("/api/v1/admin/request-types")
                            .header("X-Command-Id", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    objectMapper
                                        .createObjectNode()
                                        .put("key", requestKey)
                                        .put("name", "Managed Request")
                                        .put("category", "GENERAL")
                                        .put("workflowDefinitionId", workflowId.toString())
                                        .put("active", true)
                                        .set(
                                            "creationPolicyJson",
                                            objectMapper.createObjectNode())))))
                .andExpect(status().isCreated())
                .andReturn());

    mockMvc
        .perform(owner(get("/api/v1/admin/request-types/{id}", requestType.path("id").asText())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowDefinitionId").value(workflowId.toString()))
        .andExpect(jsonPath("$.currentPublishedVersionNo").value(1))
        .andExpect(jsonPath("$.schemaAvailable").value(true));
    mockMvc
        .perform(owner(get("/api/v1/request-types")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.key == '" + requestKey + "')]").exists());

    assertThat(detail.path("workflow").path("versionCount").asLong()).isEqualTo(1);
    assertThat(auditRepository.findAll().stream().map(event -> event.getEventType()))
        .contains("WORKFLOW_CREATED", "WORKFLOW_DRAFT_CREATED", "WORKFLOW_VERSION_PUBLISHED");
  }

  @Test
  void enforcesManagementAuthorizationAndAuditedLifecycleCommands() throws Exception {
    mockMvc.perform(user(get("/api/v1/workflows"))).andExpect(status().isForbidden());
    mockMvc.perform(user(get("/api/v1/admin/request-types"))).andExpect(status().isForbidden());

    JsonNode definition = createDefinition();
    UUID workflowId = UUID.fromString(definition.path("id").asText());
    JsonNode suspended =
        body(
            mockMvc
                .perform(
                    owner(
                        post("/api/v1/workflows/{id}/suspend", workflowId)
                            .header("X-Command-Id", UUID.randomUUID())
                            .header("If-Match", definition.path("lockVersion").asLong())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Maintenance window\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("SUSPENDED"))
                .andReturn());
    mockMvc
        .perform(
            owner(
                post("/api/v1/workflows/{id}/archive", workflowId)
                    .header("X-Command-Id", UUID.randomUUID())
                    .header("If-Match", suspended.path("lockVersion").asLong())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Retired\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lifecycle").value("ARCHIVED"));
    assertThat(auditRepository.findAll().stream().map(event -> event.getEventType()))
        .contains("WORKFLOW_SUSPENDED", "WORKFLOW_ARCHIVED");
  }

  @Test
  void publishWithWarningAcknowledgmentViaApi() throws Exception {
    String key = "WARN_ACK_" + UUID.randomUUID().toString().replace("-", "");
    JsonNode definition =
        body(
            mockMvc
                .perform(
                    owner(
                        post("/api/v1/workflows")
                            .header("X-Command-Id", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    objectMapper
                                        .createObjectNode()
                                        .put("key", key)
                                        .put("name", "Warning Ack Workflow")
                                        .put("ownerId", OWNER.toString())))))
                .andExpect(status().isCreated())
                .andReturn());
    UUID workflowId = UUID.fromString(definition.path("id").asText());

    JsonNode draft =
        body(
            mockMvc
                .perform(
                    owner(
                        post("/api/v1/workflows/{id}/draft", workflowId)
                            .header("X-Command-Id", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")))
                .andExpect(status().isCreated())
                .andReturn());
    UUID versionId = UUID.fromString(draft.path("id").asText());

    var miConfig = objectMapper.createObjectNode();
    var mi = miConfig.putObject("multiInstance");
    mi.put("collection", "${ticket.data.items}");
    mi.put("completionPolicy", "ANY");
    mi.put("remainingItemPolicy", "CANCEL_REMAINING");
    mi.put("ackRequired", true);
    var participant = miConfig.putObject("participant");
    participant.put("type", "FIXED_USER");
    participant.put("userId", UUID.randomUUID().toString());
    var actions = miConfig.putArray("allowedActions");
    actions.add("SUBMIT");
    actions.add("RETURN");

    var graphDocument = objectMapper.createObjectNode();
    graphDocument.set(
        "nodes",
        objectMapper
            .createArrayNode()
            .add(node("start", "start", "START", "Start"))
            .add(
                objectMapper
                    .createObjectNode()
                    .put("clientRef", "review")
                    .put("nodeKey", "review")
                    .put("nodeType", "REVIEW")
                    .put("name", "Review")
                    .put("configSchemaVersion", 1)
                    .set("configJson", miConfig))
            .add(node("end", "end", "END", "End")));
    graphDocument.set(
        "edges",
        objectMapper
            .createArrayNode()
            .add(
                objectMapper
                    .createObjectNode()
                    .put("clientRef", "start-review")
                    .put("sourceClientRef", "start")
                    .put("sourcePort", "STARTED")
                    .put("targetClientRef", "review")
                    .put("priority", 0)
                    .put("defaultTransition", false)
                    .put("transitionType", "NORMAL")
                    .set("configJson", objectMapper.createObjectNode()))
            .add(
                objectMapper
                    .createObjectNode()
                    .put("clientRef", "review-end")
                    .put("sourceClientRef", "review")
                    .put("sourcePort", "SUBMITTED")
                    .put("targetClientRef", "end")
                    .put("priority", 0)
                    .put("defaultTransition", false)
                    .put("transitionType", "NORMAL")
                    .set("configJson", objectMapper.createObjectNode()))
            .add(
                objectMapper
                    .createObjectNode()
                    .put("clientRef", "review-end-return")
                    .put("sourceClientRef", "review")
                    .put("sourcePort", "RETURNED")
                    .put("targetClientRef", "end")
                    .put("priority", 0)
                    .put("defaultTransition", false)
                    .put("transitionType", "NORMAL")
                    .set("configJson", objectMapper.createObjectNode())));
    var graph = objectMapper.createObjectNode().put("expectedRevision", 0);
    graph.set("graph", graphDocument);
    JsonNode graphResult =
        body(
            mockMvc
                .perform(
                    owner(
                        put(
                                "/api/v1/workflows/{id}/versions/{versionId}/graph",
                                workflowId,
                                versionId)
                            .header("X-Command-Id", UUID.randomUUID())
                            .header("If-Match", draft.path("lockVersion").asLong())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(graph))))
                .andExpect(status().isOk())
                .andReturn());

    long draftLock = graphResult.path("draft").path("lockVersion").asLong();
    long revision = graphResult.path("draft").path("revision").asLong();

    mockMvc
        .perform(
            owner(
                post("/api/v1/workflows/{id}/versions/{versionId}/publish", workflowId, versionId)
                    .header("X-Command-Id", UUID.randomUUID())
                    .header("If-Match", draftLock)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedRevision\":" + revision + "}")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("WORKFLOW_VALIDATION_ACK_REQUIRED"));

    var ackPublish =
        objectMapper
            .createObjectNode()
            .put("expectedRevision", revision)
            .set(
                "acknowledgedWarnings",
                objectMapper
                    .createArrayNode()
                    .add("MULTI_INSTANCE_CANCEL_REMAINING_ACK_REQUIRED"));
    mockMvc
        .perform(
            owner(
                post("/api/v1/workflows/{id}/versions/{versionId}/publish", workflowId, versionId)
                    .header("X-Command-Id", UUID.randomUUID())
                    .header("If-Match", draftLock)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ackPublish))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
  }

  private JsonNode createDefinition() throws Exception {
    return body(
        mockMvc
            .perform(
                owner(
                    post("/api/v1/workflows")
                        .header("X-Command-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                objectMapper
                                    .createObjectNode()
                                    .put(
                                        "key",
                                        "LIFECYCLE_"
                                            + UUID.randomUUID().toString().replace("-", ""))
                                    .put("name", "Lifecycle workflow")
                                    .put("ownerId", OWNER.toString())))))
            .andExpect(status().isCreated())
            .andReturn());
  }

  private com.fasterxml.jackson.databind.node.ObjectNode node(
      String ref, String key, String type, String name) {
    return objectMapper
        .createObjectNode()
        .put("clientRef", ref)
        .put("nodeKey", key)
        .put("nodeType", type)
        .put("name", name)
        .put("configSchemaVersion", 1)
        .set("configJson", objectMapper.createObjectNode());
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder owner(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
    return request
        .header("X-Actor-Id", OWNER)
        .header("X-Actor-Name", "Owner")
        .header("X-Actor-Roles", "WORKFLOW_OWNER");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder user(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
    return request
        .header("X-Actor-Id", "10000000-0000-4000-8000-000000000001")
        .header("X-Actor-Name", "User")
        .header("X-Actor-Roles", "USER");
  }

  private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
