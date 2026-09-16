package com.fpt.workflow.ticketcategory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Guards the V41→V42 upgrade against the real legacy field shape: {@code fieldId} is a UUID and
 * the actual identity is {@code key} (e.g. leaveType). An empty-database upgrade used to hide that
 * the backfill selected {@code fieldId} as {@code field_key}, violating ck_form_fields_key.
 */
@Testcontainers
class BusinessIntentMigrationWithLegacyDataIT {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_v42_legacy_upgrade_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Test
  void upgradesV41WithExistingLegacyRowsWithoutViolatingFormFieldKeyConstraint() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("41"))
        .load()
        .migrate();

    UUID owner = UUID.randomUUID();
    UUID actor = owner;
    UUID definitionId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID requestTypeId = UUID.randomUUID();
    UUID formId = UUID.randomUUID();
    var mapper = new ObjectMapper();

    try (var connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      try (var statement = connection.createStatement()) {
        statement.execute(
            "INSERT INTO workflow_definitions(id,key,name,description,lifecycle,owner_id,created_by,created_at,updated_at)"
                + " VALUES ('"
                + definitionId
                + "','"
                + "legacy_def_"
                + UUID.randomUUID().toString().replace("-", "")
                + "','Legacy','desc','ACTIVE','"
                + owner
                + "','"
                + actor
                + "',now(),now())");
        statement.execute(
            "INSERT INTO workflow_versions(id,definition_id,version_no,status,revision,created_by,created_at)"
                + " VALUES ('"
                + versionId
                + "','"
                + definitionId
                + "',1,'DRAFT',0,'"
                + actor
                + "',now())");

        ArrayNode fields = mapper.createArrayNode();
        fields.add(
            legacyField(
                mapper, UUID.randomUUID(), "leaveType", "Leave Type", "STRING", false, 0));
        fields.add(
            legacyField(mapper, UUID.randomUUID(), "startDate", "Start Date", "DATE", true, 1));
        fields.add(
            legacyField(mapper, UUID.randomUUID(), "amount", "Amount", "NUMBER", false, 2));
        ObjectNode schema = mapper.createObjectNode();
        schema.set("fields", fields);
        schema.put("type", "TICKET_FORM");
        schema.put("formKey", "ticket");
        String schemaJson = schema.toString().replace("'", "''");

        statement.execute(
            "INSERT INTO request_types(id,key,name,description,category,workflow_definition_id,active,created_at,updated_at) VALUES('"
                + requestTypeId
                + "','leave_request','Leave Request','legacy','HR','"
                + definitionId
                + "',true,now(),now())");
        statement.execute(
            "INSERT INTO workflow_forms(id,workflow_version_id,form_key,form_type,schema_json,schema_checksum) VALUES('"
                + formId
                + "','"
                + versionId
                + "','ticket','TICKET_FORM','"
                + schemaJson
                + "'::jsonb,'legacy-checksum-"
                + formId
                + "')");
        try (var r =
            statement.executeQuery(
                "SELECT COUNT(*) FROM workflow_forms WHERE workflow_version_id='"
                    + versionId
                    + "'")) {
          r.next();
          assertThat(r.getInt(1)).isEqualTo(1);
        }

        // Production order: the contract (incl. forms) is authored while DRAFT, then published.
        statement.execute(
            "UPDATE workflow_versions SET status='PUBLISHED',checksum='legacy-checksum',"
                + "execution_package_json='{}'::jsonb,published_by='"
                + actor
                + "',published_at=now() WHERE id='"
                + versionId
                + "'");
        statement.execute(
            "UPDATE workflow_definitions SET current_published_version_id='"
                + versionId
                + "' WHERE id='"
                + definitionId
                + "'");
      }
    }

    Flyway current =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load();
    var currentResult = current.migrate();
    assertThat(currentResult.migrationsExecuted).isEqualTo(4);
    assertThat(currentVersion()).isEqualTo("45");

    // The backfill must have used the display key, not the opaque UUID fieldId.
    assertThat(formFieldsMatch("leaveType")).isTrue();
    assertThat(formFieldsMatch("startDate")).isTrue();
    assertThat(formFieldsMatch("amount")).isTrue();
    assertThat(workflowInputsMatch("leaveType")).isTrue();
    assertThat(workflowInputsMatch("amount")).isTrue();
  }

  private ObjectNode legacyField(
      ObjectMapper mapper, UUID fieldId, String key, String label, String type, boolean sensitive, int order) {
    ObjectNode field = mapper.createObjectNode();
    field.put("fieldId", fieldId.toString());
    field.put("key", key);
    field.put("label", label);
    ObjectNode typeJson = mapper.createObjectNode();
    typeJson.put("type", type);
    typeJson.put("nullable", false);
    field.set("type", typeJson);
    field.put("sensitive", sensitive);
    ObjectNode requirement = mapper.createObjectNode();
    requirement.put("mode", "ALWAYS");
    field.set("requirement", requirement);
    ObjectNode visibility = mapper.createObjectNode();
    visibility.put("mode", "ALWAYS");
    field.set("visibility", visibility);
    ObjectNode editability = mapper.createObjectNode();
    editability.put("mode", "EDITABLE");
    field.set("editability", editability);
    field.set("validation", mapper.createObjectNode());
    // Omit null/empty properties so the row passes the strict post-migration parsers.
    field.put("order", order);
    return field;
  }

  private boolean formFieldsMatch(String fieldKey) throws Exception {
    return scalar(
        "SELECT EXISTS(SELECT 1 FROM form_fields WHERE field_key = '" + fieldKey.replace("'", "''") + "')", null);
  }

  private boolean workflowInputsMatch(String inputKey) throws Exception {
    return scalar(
        "SELECT EXISTS(SELECT 1 FROM workflow_inputs WHERE input_key = '" + inputKey.replace("'", "''") + "')",
        null);
  }

  private String currentVersion() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement();
        var rs =
            statement.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private boolean scalar(String sql, String ignored) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement();
        var rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getBoolean(1);
    }
  }
}
