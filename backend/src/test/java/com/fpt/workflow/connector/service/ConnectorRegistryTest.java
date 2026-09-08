package com.fpt.workflow.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.connector.domain.ConnectorAction;
import com.fpt.workflow.connector.domain.ConnectorActionVersion;
import com.fpt.workflow.connector.domain.ConnectorDefinition;
import com.fpt.workflow.connector.repository.ConnectorActionRepository;
import com.fpt.workflow.connector.repository.ConnectorActionVersionRepository;
import com.fpt.workflow.connector.repository.ConnectorDefinitionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.RoleKey;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class ConnectorRegistryTest {

  private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

  private ConnectorDefinitionRepository connectorRepo;
  private ConnectorActionRepository actionRepo;
  private ConnectorActionVersionRepository versionRepo;
  private DefaultConnectorRegistry registry;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    connectorRepo = mock(ConnectorDefinitionRepository.class);
    actionRepo = mock(ConnectorActionRepository.class);
    versionRepo = mock(ConnectorActionVersionRepository.class);
    registry = new DefaultConnectorRegistry(connectorRepo, actionRepo, versionRepo);
    objectMapper = new ObjectMapper();
  }

  @Test
  void actionV2VsV3_retrieval_returnsCorrectPinnedVersion() {
    UUID connectorId = UUID.randomUUID();
    UUID actionId = UUID.randomUUID();

    ConnectorDefinition connector =
        ConnectorDefinition.create(
            connectorId,
            "ERP_CONNECTOR",
            "ERP System",
            "REST",
            "restConnectorHandler",
            "ACTIVE",
            JsonNodeFactory.instance.objectNode().put("baseUrl", "https://erp.example.com"),
            "vault://credentials/erp",
            NOW);

    ConnectorAction action =
        ConnectorAction.create(
            actionId, connectorId, "CREATE_INVOICE", "Create Invoice Action", "ACTIVE", NOW);

    ObjectNode v2Schema = JsonNodeFactory.instance.objectNode();
    v2Schema.put("type", "object");
    v2Schema.putObject("properties").putObject("amount").put("type", "number");

    ObjectNode v3Schema = JsonNodeFactory.instance.objectNode();
    v3Schema.put("type", "object");
    v3Schema.putObject("properties").putObject("amount").put("type", "number");
    v3Schema.withObject("/properties").putObject("currency").put("type", "string");

    ConnectorActionVersion v2 =
        ConnectorActionVersion.publish(
            UUID.randomUUID(),
            actionId,
            2,
            v2Schema,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            NOW);

    ConnectorActionVersion v3 =
        ConnectorActionVersion.publish(
            UUID.randomUUID(),
            actionId,
            3,
            v3Schema,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            NOW);

    when(connectorRepo.findByKey("ERP_CONNECTOR")).thenReturn(Optional.of(connector));
    when(actionRepo.findByConnectorIdAndActionKey(connectorId, "CREATE_INVOICE"))
        .thenReturn(Optional.of(action));
    when(versionRepo.findByConnectorActionIdAndVersionNo(actionId, 2)).thenReturn(Optional.of(v2));
    when(versionRepo.findByConnectorActionIdAndVersionNo(actionId, 3)).thenReturn(Optional.of(v3));

    ConnectorActionVersion loadedV2 =
        registry.requireActionVersion("ERP_CONNECTOR", "CREATE_INVOICE", 2);
    assertThat(loadedV2.getVersionNo()).isEqualTo(2);
    assertThat(loadedV2.getInputSchemaJson().at("/properties/currency").isMissingNode()).isTrue();

    ConnectorActionVersion loadedV3 =
        registry.requireActionVersion("ERP_CONNECTOR", "CREATE_INVOICE", 3);
    assertThat(loadedV3.getVersionNo()).isEqualTo(3);
    assertThat(loadedV3.getInputSchemaJson().at("/properties/currency/type").asText())
        .isEqualTo("string");
  }

  @Test
  void actionVersions_areImmutableOncePublished() {
    ConnectorActionVersion v1 =
        ConnectorActionVersion.publish(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            NOW);

    assertThatThrownBy(v1::requireMutable)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("immutable");

    v1.deprecate();
    assertThat(v1.getStatus()).isEqualTo("DEPRECATED");
    assertThatThrownBy(v1::requireMutable)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("immutable");
  }

  @Test
  void permission_allowlist_allowedRole_succeeds() {
    ObjectNode policy = JsonNodeFactory.instance.objectNode();
    policy.putArray("allowedRoles").add("WORKFLOW_OWNER").add("ADMIN");

    ConnectorActionVersion version =
        ConnectorActionVersion.publish(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            policy,
            NOW);

    ActorContext owner =
        new ActorContext(UUID.randomUUID(), "owner", Set.of(RoleKey.WORKFLOW_OWNER), Set.of());
    registry.validateActionPermission(version, owner);
  }

  @Test
  void permission_allowlist_unauthorizedActor_throwsAccessDenied() {
    ObjectNode policy = JsonNodeFactory.instance.objectNode();
    policy.putArray("allowedRoles").add("ADMIN");

    ConnectorActionVersion version =
        ConnectorActionVersion.publish(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            policy,
            NOW);

    ActorContext editor =
        new ActorContext(UUID.randomUUID(), "editor", Set.of(RoleKey.WORKFLOW_EDITOR), Set.of());
    assertThatThrownBy(() -> registry.validateActionPermission(version, editor))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("not permitted to use action version");
  }

  @Test
  void permission_allowlist_allowedUser_succeeds() {
    UUID allowedUserId = UUID.randomUUID();
    ObjectNode policy = JsonNodeFactory.instance.objectNode();
    policy.putArray("allowedUsers").add(allowedUserId.toString());

    ConnectorActionVersion version =
        ConnectorActionVersion.publish(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            policy,
            NOW);

    ActorContext allowedActor =
        new ActorContext(allowedUserId, "specialUser", Set.of(RoleKey.USER), Set.of());
    registry.validateActionPermission(version, allowedActor);

    ActorContext otherActor =
        new ActorContext(UUID.randomUUID(), "otherUser", Set.of(RoleKey.USER), Set.of());
    assertThatThrownBy(() -> registry.validateActionPermission(version, otherActor))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void secretMasking_directSecretForbiddenInConnectorConfig() {
    ObjectNode secretConfig = JsonNodeFactory.instance.objectNode();
    secretConfig.put("apiKey", "raw_secret_key_12345");

    assertThatThrownBy(
            () ->
                ConnectorDefinition.create(
                    UUID.randomUUID(),
                    "PAYMENT_GATEWAY",
                    "Payment Gateway",
                    "REST",
                    "restHandler",
                    "ACTIVE",
                    secretConfig,
                    null,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Direct secret storage forbidden");
  }

  @Test
  void secretMasking_vaultReferenceAllowedInConnectorConfig() {
    ObjectNode validConfig = JsonNodeFactory.instance.objectNode();
    validConfig.put("apiKeyRef", "vault://secrets/payment/key");

    ConnectorDefinition def =
        ConnectorDefinition.create(
            UUID.randomUUID(),
            "PAYMENT_GATEWAY",
            "Payment Gateway",
            "REST",
            "restHandler",
            "ACTIVE",
            validConfig,
            "vault://secrets/payment/cert",
            NOW);
    assertThat(def.getCredentialRef()).isEqualTo("vault://secrets/payment/cert");
  }

  @Test
  void secretMasking_directSecretForbiddenInActionExecutionConfig() {
    ObjectNode executionConfig = JsonNodeFactory.instance.objectNode();
    executionConfig.put("password", "super_secret_password");

    assertThatThrownBy(
            () ->
                ConnectorActionVersion.publish(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(),
                    executionConfig,
                    JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Direct secret storage forbidden");
  }

  @Test
  void secretMasking_nestedAndArraySecretsAreRejected() {
    ObjectNode nested = JsonNodeFactory.instance.objectNode();
    nested.putObject("transport").put("authorizationToken", "raw-token");

    assertThatThrownBy(() -> ConnectorDefinition.validateNoSecrets(nested))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("$config.transport.authorizationToken");

    ObjectNode arrayValue = JsonNodeFactory.instance.objectNode();
    arrayValue.putArray("profiles").addObject().put("password", "raw-password");
    assertThatThrownBy(() -> ConnectorDefinition.validateNoSecrets(arrayValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("$config.profiles[0].password");
  }

  @Test
  void validateNodeBinding_rejectsInvalidOrSecretNodeConfig() {
    ActorContext admin =
        new ActorContext(UUID.randomUUID(), "admin", Set.of(RoleKey.ADMIN), Set.of());

    ObjectNode missingCoords = JsonNodeFactory.instance.objectNode();
    missingCoords.put("connectorKey", "ERP");
    assertThatThrownBy(() -> registry.validateNodeBinding(missingCoords, admin))
        .isInstanceOf(IllegalArgumentException.class);

    ObjectNode secretNodeConfig = JsonNodeFactory.instance.objectNode();
    secretNodeConfig.put("connectorKey", "ERP");
    secretNodeConfig.put("actionKey", "INVOICE");
    secretNodeConfig.put("actionVersion", 1);
    secretNodeConfig.put("secretPassword", "raw-secret");

    assertThatThrownBy(() -> registry.validateNodeBinding(secretNodeConfig, admin))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Direct secret storage forbidden");
  }
}
