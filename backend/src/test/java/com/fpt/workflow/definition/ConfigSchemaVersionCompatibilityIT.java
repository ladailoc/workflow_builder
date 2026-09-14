package com.fpt.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.validation.ValidationCompilation;
import com.fpt.workflow.definition.validation.ValidationDefinition;
import com.fpt.workflow.definition.validation.WorkflowValidationCompiler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P2-07: published snapshots remain executable after the current config schema evolves.
 * V1 published snapshots (configSchemaVersion=1) continue to compile with a future-compatible
 * compiler; only future versions (schemaVersion > currentConfigSchemaVersion) fail.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConfigSchemaVersionCompatibilityIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("config_schema_compat_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowValidationCompiler compiler;
  @Autowired private ObjectMapper objectMapper;

  private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000001501");

  @Test
  void publishedV1Snapshot_remainsValid_afterPlatformCurrentVersionIsV2() {
    WorkflowVersion draft = version();
    NodeDefinition start = node(draft, "start", "START", 1, objectMapper.createObjectNode());
    com.fasterxml.jackson.databind.node.ObjectNode reviewCfg =
        objectMapper.createObjectNode();
    com.fasterxml.jackson.databind.node.ObjectNode participant =
        reviewCfg.putObject("participant");
    participant.put("type", "FIXED_USER");
    participant.put("userId", UUID.randomUUID().toString());
    reviewCfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    NodeDefinition review = node(draft, "review", "REVIEW", 1, reviewCfg);
    NodeDefinition end = node(draft, "end", "END", 1, objectMapper.createObjectNode().put("outcome", "COMPLETED"));
    EdgeDefinition e1 = edge(draft, start, "STARTED", review);
    EdgeDefinition e2 = edge(draft, review, "SUBMITTED", end);
    EdgeDefinition e3 = edge(draft, review, "RETURNED", end);

    ValidationCompilation v1Compilation =
        compiler.compile(new ValidationDefinition(draft, List.of(start, review, end), List.of(e1, e2, e3), List.of(), List.of()));
    // v1 edge path via: runtime knows how to interpret historical config.
    // No published JSON is rewritten: this compilation uses the historical schema version as-is.
    assertThat(v1Compilation.issues().toString()).doesNotContain("CONFIG_SCHEMA_VERSION");
    assertThat(v1Compilation.valid()).isTrue();
  }

  @Test
  void futureConfigSchemaVersion_isRejected() {
    WorkflowVersion draft = version();
    // A draft node with a schema version from the future cannot be validated or published.
    NodeDefinition start = node(draft, "start", "START", 99, objectMapper.createObjectNode());
    NodeDefinition end = node(draft, "end", "END", 99, objectMapper.createObjectNode());
    EdgeDefinition edge = edge(draft, start, "STARTED", end);
    ValidationCompilation compilation =
        compiler.compile(new ValidationDefinition(draft, List.of(start, end), List.of(edge), List.of(), List.of()));
    assertThat(compilation.issues())
        .anyMatch(
            i ->
                "NODE_CONFIG_SCHEMA_VERSION_UNKNOWN".equals(i.code())
                    || "NODE.CONFIG_SCHEMA_VERSION_UNKNOWN".equals(i.code())
                    || i.code().contains("CONFIG_SCHEMA_VERSION_UNKNOWN"));
  }

  private WorkflowVersion version() {
    return WorkflowVersion.createDraft(
        UUID.randomUUID(), UUID.randomUUID(), 1, null, null, ACTOR, Instant.EPOCH);
  }

  private NodeDefinition node(
      WorkflowVersion version, String key, String type, int configSchemaVersion, com.fasterxml.jackson.databind.JsonNode config) {
    return NodeDefinition.create(
        UUID.randomUUID(), version.getId(), key, type, key, null, configSchemaVersion, config, null, null, objectMapper.createObjectNode());
  }

  private EdgeDefinition edge(WorkflowVersion version, NodeDefinition source, String port, NodeDefinition target) {
    return EdgeDefinition.create(
        UUID.randomUUID(),
        version.getId(),
        source.getId(),
        port,
        target.getId(),
        null,
        0,
        false,
        com.fpt.workflow.definition.domain.TransitionType.NORMAL,
        null,
        objectMapper.createObjectNode());
  }
}
