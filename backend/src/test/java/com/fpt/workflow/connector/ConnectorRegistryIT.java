package com.fpt.workflow.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.connector.domain.ConnectorAction;
import com.fpt.workflow.connector.domain.ConnectorActionVersion;
import com.fpt.workflow.connector.domain.ConnectorDefinition;
import com.fpt.workflow.connector.repository.ConnectorActionRepository;
import com.fpt.workflow.connector.repository.ConnectorActionVersionRepository;
import com.fpt.workflow.connector.repository.ConnectorDefinitionRepository;
import com.fpt.workflow.connector.service.ConnectorManagementService;
import com.fpt.workflow.connector.service.ConnectorRegistry;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.publish.WorkflowPublishService;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.validation.ValidationCompilation;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ConnectorRegistryIT {

  private static final UUID TECH_ADMIN_ID = UUID.fromString("10000000-0000-4000-8000-000000000099");
  private static final ActorContext TECH_ADMIN =
      new ActorContext(TECH_ADMIN_ID, "techAdmin", Set.of(RoleKey.ADMIN), Set.of());

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("connector_registry_it")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private ConnectorManagementService managementService;
  @Autowired private ConnectorRegistry connectorRegistry;
  @Autowired private ConnectorDefinitionRepository connectorRepo;
  @Autowired private ConnectorActionRepository actionRepo;
  @Autowired private ConnectorActionVersionRepository versionRepo;
  @Autowired private WorkflowPublishService publishService;
  @Autowired private WorkflowValidationService validationService;
  @Autowired private WorkflowDefinitionRepository definitionRepo;
  @Autowired private WorkflowVersionRepository versionRepository;
  @Autowired private NodeDefinitionRepository nodeRepo;
  @Autowired private EdgeDefinitionRepository edgeRepo;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  private NodeDefinition createNode(
      UUID versionId, String key, String type, int configSchemaVersion, ObjectNode config) {
    return NodeDefinition.create(
        UUID.randomUUID(),
        versionId,
        key,
        type,
        key,
        null,
        configSchemaVersion,
        config != null ? config : objectMapper.createObjectNode(),
        null,
        null,
        objectMapper.createObjectNode());
  }

  private EdgeDefinition createEdge(
      UUID versionId,
      UUID sourceId,
      String sourcePort,
      UUID targetId,
      int priority,
      boolean isDefault) {
    return EdgeDefinition.create(
        UUID.randomUUID(),
        versionId,
        sourceId,
        sourcePort,
        targetId,
        null,
        priority,
        isDefault,
        TransitionType.NORMAL,
        null,
        objectMapper.createObjectNode());
  }

  @Test
  void actionV2VsV3_registrationAndRetrieval() {
    ConnectorDefinition connector =
        managementService.registerConnector(
            "PAYMENT_GATEWAY",
            "Payment Gateway Connector",
            "REST",
            "httpHandler",
            JsonNodeFactory.instance.objectNode().put("baseUrl", "https://pay.example.com"),
            "vault://pay/token",
            TECH_ADMIN);

    ConnectorAction action =
        managementService.registerAction(
            "PAYMENT_GATEWAY", "CHARGE", "Charge Customer", TECH_ADMIN);

    ObjectNode v2Input = JsonNodeFactory.instance.objectNode();
    v2Input.put("type", "object");
    v2Input.putObject("properties").putObject("amount").put("type", "number");

    ObjectNode v3Input = JsonNodeFactory.instance.objectNode();
    v3Input.put("type", "object");
    v3Input.putObject("properties").putObject("amount").put("type", "number");
    v3Input.withObject("/properties").putObject("currency").put("type", "string");

    ConnectorActionVersion v2 =
        managementService.publishActionVersion(
            "PAYMENT_GATEWAY",
            "CHARGE",
            2,
            v2Input,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            TECH_ADMIN);

    ConnectorActionVersion v3 =
        managementService.publishActionVersion(
            "PAYMENT_GATEWAY",
            "CHARGE",
            3,
            v3Input,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            TECH_ADMIN);

    ConnectorActionVersion loadedV2 =
        connectorRegistry.requireActionVersion("PAYMENT_GATEWAY", "CHARGE", 2);
    assertThat(loadedV2.getVersionNo()).isEqualTo(2);
    assertThat(loadedV2.getInputSchemaJson().at("/properties/currency").isMissingNode()).isTrue();

    ConnectorActionVersion loadedV3 =
        connectorRegistry.requireActionVersion("PAYMENT_GATEWAY", "CHARGE", 3);
    assertThat(loadedV3.getVersionNo()).isEqualTo(3);
    assertThat(loadedV3.getInputSchemaJson().at("/properties/currency/type").asText())
        .isEqualTo("string");
  }

  @Test
  void databaseImmutabilityTrigger_preventsModifyingPublishedActionVersion() {
    managementService.registerConnector(
        "CRM_GATEWAY",
        "CRM Gateway",
        "REST",
        "httpHandler",
        JsonNodeFactory.instance.objectNode(),
        "vault://crm/key",
        TECH_ADMIN);

    managementService.registerAction("CRM_GATEWAY", "SYNC", "Sync CRM", TECH_ADMIN);

    ConnectorActionVersion v1 =
        managementService.publishActionVersion(
            "CRM_GATEWAY",
            "SYNC",
            1,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            TECH_ADMIN);

    versionRepo.flush();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE connector_action_versions SET input_schema_json = '{\"mutated\":true}'::jsonb WHERE id = ?",
                    v1.getId()))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("immutable");
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void oldPublishedWorkflowStaysPinnedToV2_evenAfterV3IsPublished() {
    managementService.registerConnector(
        "INVENTORY_SYSTEM",
        "Inventory System",
        "REST",
        "inventoryHandler",
        JsonNodeFactory.instance.objectNode(),
        "vault://inv/cred",
        TECH_ADMIN);

    managementService.registerAction("INVENTORY_SYSTEM", "CHECK_STOCK", "Check Stock", TECH_ADMIN);

    ObjectNode v2Input = JsonNodeFactory.instance.objectNode();
    v2Input.put("type", "object");
    v2Input.putObject("properties").putObject("sku").put("type", "string");

    managementService.publishActionVersion(
        "INVENTORY_SYSTEM",
        "CHECK_STOCK",
        2,
        v2Input,
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        TECH_ADMIN);

    // Build workflow with SYSTEM_ACTION pinning v2
    UUID wfDefId = UUID.randomUUID();
    Instant now = Instant.now().minusSeconds(60);
    WorkflowDefinition definition =
        definitionRepo.save(
            WorkflowDefinition.create(
                wfDefId,
                "INVENTORY_FLOW",
                "Inventory Flow",
                "Check stock workflow",
                TECH_ADMIN_ID,
                TECH_ADMIN_ID,
                now));

    UUID versionId = UUID.randomUUID();
    WorkflowVersion draft =
        versionRepository.save(
            WorkflowVersion.createDraft(versionId, wfDefId, 1, null, null, TECH_ADMIN_ID, now));
    definition.assignActiveDraft(draft.getId(), now);
    definitionRepo.save(definition);

    NodeDefinition start = createNode(versionId, "START", "START", 1, null);

    ObjectNode actionConfig = JsonNodeFactory.instance.objectNode();
    actionConfig.put("connectorKey", "INVENTORY_SYSTEM");
    actionConfig.put("actionKey", "CHECK_STOCK");
    actionConfig.put("actionVersion", 2);
    actionConfig.put("credentialRef", "vault://inv/cred");

    NodeDefinition actionNode =
        createNode(versionId, "CHECK_STOCK_NODE", "SYSTEM_ACTION", 1, actionConfig);

    NodeDefinition end = createNode(versionId, "END", "END", 1, null);

    nodeRepo.saveAll(List.of(start, actionNode, end));

    EdgeDefinition e1 =
        createEdge(versionId, start.getId(), "STARTED", actionNode.getId(), 0, false);
    EdgeDefinition e2 = createEdge(versionId, actionNode.getId(), "SUCCESS", end.getId(), 0, false);
    EdgeDefinition e3 = createEdge(versionId, actionNode.getId(), "ERROR", end.getId(), 0, false);

    edgeRepo.saveAll(List.of(e1, e2, e3));

    // Validate and publish workflow
    ValidationCompilation compilation = validationService.compileCurrent(versionId);
    assertThat(compilation.valid())
        .withFailMessage("Validation failed with issues: " + compilation.issues())
        .isTrue();
    assertThat(compilation.publishable()).isTrue();

    WorkflowPublishService.PublishResult publishResult =
        publishService.publish(
            versionId, new ExpectedVersion(0), 0, new CommandId(UUID.randomUUID()));
    assertThat(publishResult.status()).isEqualTo(WorkflowVersionStatus.PUBLISHED);

    WorkflowVersion published = versionRepository.findById(versionId).orElseThrow();
    String originalChecksum = published.getChecksum();

    // Verify pinned execution package specifies v2
    JsonNode actionNodeInPackage = null;
    for (JsonNode n : published.getExecutionPackageJson().path("nodes")) {
      if ("CHECK_STOCK_NODE".equals(n.path("key").asText())) {
        actionNodeInPackage = n;
        break;
      }
    }
    assertThat(actionNodeInPackage).isNotNull();
    assertThat(actionNodeInPackage.at("/config/actionVersion").asInt()).isEqualTo(2);

    // Later: Technical Admin publishes v3 with breaking change
    ObjectNode v3Input = JsonNodeFactory.instance.objectNode();
    v3Input.put("type", "object");
    v3Input.putObject("properties").putObject("warehouseId").put("type", "string");

    managementService.publishActionVersion(
        "INVENTORY_SYSTEM",
        "CHECK_STOCK",
        3,
        v3Input,
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        TECH_ADMIN);

    // Old Published Workflow stays strictly pinned to v2; its checksum and package are untouched
    WorkflowVersion postV3Published = versionRepository.findById(versionId).orElseThrow();
    assertThat(postV3Published.getChecksum()).isEqualTo(originalChecksum);
    JsonNode postV3ActionNode = null;
    for (JsonNode n : postV3Published.getExecutionPackageJson().path("nodes")) {
      if ("CHECK_STOCK_NODE".equals(n.path("key").asText())) {
        postV3ActionNode = n;
        break;
      }
    }
    assertThat(postV3ActionNode).isNotNull();
    assertThat(postV3ActionNode.at("/config/actionVersion").asInt()).isEqualTo(2);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void permission_allowlistEnforcedDuringValidation() {
    managementService.registerConnector(
        "SECURE_VAULT",
        "Secure Vault",
        "REST",
        "vaultHandler",
        JsonNodeFactory.instance.objectNode(),
        "vault://keys/sec",
        TECH_ADMIN);

    managementService.registerAction("SECURE_VAULT", "SIGN", "Sign Key", TECH_ADMIN);

    ObjectNode adminOnlyPolicy = JsonNodeFactory.instance.objectNode();
    adminOnlyPolicy.putArray("allowedRoles").add("ADMIN");

    managementService.publishActionVersion(
        "SECURE_VAULT",
        "SIGN",
        1,
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        adminOnlyPolicy,
        TECH_ADMIN);

    UUID wfDefId = UUID.randomUUID();
    Instant now = Instant.now().minusSeconds(60);
    definitionRepo.save(
        WorkflowDefinition.create(
            wfDefId,
            "VAULT_FLOW",
            "Vault Flow",
            "Vault workflow",
            TECH_ADMIN_ID,
            TECH_ADMIN_ID,
            now));

    UUID versionId = UUID.randomUUID();
    WorkflowVersion draft =
        versionRepository.save(
            WorkflowVersion.createDraft(versionId, wfDefId, 1, null, null, TECH_ADMIN_ID, now));

    NodeDefinition start = createNode(versionId, "START", "START", 1, null);

    ObjectNode actionConfig = JsonNodeFactory.instance.objectNode();
    actionConfig.put("connectorKey", "SECURE_VAULT");
    actionConfig.put("actionKey", "SIGN");
    actionConfig.put("actionVersion", 1);

    NodeDefinition actionNode =
        createNode(versionId, "SIGN_NODE", "SYSTEM_ACTION", 1, actionConfig);

    NodeDefinition end = createNode(versionId, "END", "END", 1, null);

    nodeRepo.saveAll(List.of(start, actionNode, end));

    EdgeDefinition e1 =
        createEdge(versionId, start.getId(), "STARTED", actionNode.getId(), 0, false);
    EdgeDefinition e2 = createEdge(versionId, actionNode.getId(), "SUCCESS", end.getId(), 0, false);
    EdgeDefinition e3 = createEdge(versionId, actionNode.getId(), "ERROR", end.getId(), 0, false);

    edgeRepo.saveAll(List.of(e1, e2, e3));

    // Validated by WORKFLOW_OWNER (not ADMIN) -> permission denied issue
    ValidationCompilation compilation = validationService.compileCurrent(versionId);
    assertThat(compilation.valid()).isFalse();
    assertThat(compilation.publishable()).isFalse();
    assertThat(compilation.issues())
        .anyMatch(issue -> "CONNECTOR_ACTION_PERMISSION_DENIED".equals(issue.code()));
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void secretMasking_rawSecretInNodeConfigFailsValidation() {
    managementService.registerConnector(
        "DOC_SIGN",
        "Doc Sign",
        "REST",
        "docHandler",
        JsonNodeFactory.instance.objectNode(),
        "vault://doc/key",
        TECH_ADMIN);

    managementService.registerAction("DOC_SIGN", "SIGN_DOC", "Sign Doc", TECH_ADMIN);

    managementService.publishActionVersion(
        "DOC_SIGN",
        "SIGN_DOC",
        1,
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        TECH_ADMIN);

    UUID wfDefId = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-08T10:00:00Z");
    definitionRepo.save(
        WorkflowDefinition.create(
            wfDefId,
            "SECRET_FLOW",
            "Secret Flow",
            "Secret test workflow",
            TECH_ADMIN_ID,
            TECH_ADMIN_ID,
            now));

    UUID versionId = UUID.randomUUID();
    WorkflowVersion draft =
        versionRepository.save(
            WorkflowVersion.createDraft(versionId, wfDefId, 1, null, null, TECH_ADMIN_ID, now));

    NodeDefinition start = createNode(versionId, "START", "START", 1, null);

    ObjectNode badConfig = JsonNodeFactory.instance.objectNode();
    badConfig.put("connectorKey", "DOC_SIGN");
    badConfig.put("actionKey", "SIGN_DOC");
    badConfig.put("actionVersion", 1);
    badConfig.put("password", "raw_plaintext_password_here");

    NodeDefinition actionNode = createNode(versionId, "SIGN_NODE", "SYSTEM_ACTION", 1, badConfig);

    NodeDefinition end = createNode(versionId, "END", "END", 1, null);

    nodeRepo.saveAll(List.of(start, actionNode, end));

    EdgeDefinition e1 =
        createEdge(versionId, start.getId(), "STARTED", actionNode.getId(), 0, false);
    EdgeDefinition e2 = createEdge(versionId, actionNode.getId(), "SUCCESS", end.getId(), 0, false);
    EdgeDefinition e3 = createEdge(versionId, actionNode.getId(), "ERROR", end.getId(), 0, false);

    edgeRepo.saveAll(List.of(e1, e2, e3));

    ValidationCompilation compilation = validationService.compileCurrent(versionId);
    assertThat(compilation.valid()).isFalse();
    assertThat(compilation.publishable()).isFalse();
    assertThat(compilation.issues())
        .anyMatch(issue -> "SECRET_STORAGE_FORBIDDEN".equals(issue.code()));
  }
}
