package com.fpt.workflow.operations.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the real V40 → V41 upgrade boundary: an existing V40 schema validates against the
 * historical migration set, then a subsequent migrate applies exactly V41 (no replay of V1–V40),
 * creating the platform-retention objects and replacing {@code guard_runtime_history()} while
 * preserving the terminal-Event immutability invariant (FAILED included).
 */
@Testcontainers
class PlatformRetentionUpgradeMigrationIT {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_v40_upgrade_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Test
  void upgradesExistingV40SchemaToV41WithoutReplayingHistory() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("40"))
        .load()
        .migrate();

    assertThat(currentVersion()).isEqualTo("40");

    // Validate the applied V1–V40 history against resolved migrations. V41 is legitimately
    // pending at this boundary, so it is excluded from this validation pass only.
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .ignoreMigrationPatterns("*:pending")
        .load()
        .validate();

    Flyway current =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("41"))
            .load();
    assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(currentVersion()).isEqualTo("41");
    assertThat(tableExists("retention_legal_holds")).isTrue();
    assertThat(indexExists("uq_retention_actions_effect_once")).isTrue();

    // Upgrade must not regress the runtime-history contract: the replaced guard function keeps
    // every terminal Event status immutable, including FAILED.
    String guardBody = guardRuntimeHistorySource();
    assertThat(guardBody)
        .contains("'FAILED'")
        .contains("'COMPLETED'")
        .contains("'CANCELLED'")
        .contains("'TERMINATED'");
  }

  private String currentVersion() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement();
        var result =
            statement.executeQuery(
                "select version from flyway_schema_history where success order by installed_rank desc limit 1")) {
      result.next();
      return result.getString(1);
    }
  }

  private String guardRuntimeHistorySource() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement =
            connection.prepareStatement(
                "select prosrc from pg_proc where proname = 'guard_runtime_history'")) {
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).as("guard_runtime_history exists").isTrue();
        return result.getString(1);
      }
    }
  }

  private boolean tableExists(String name) throws Exception {
    return exists("select to_regclass('public.' || ?) is not null", name);
  }

  private boolean indexExists(String name) throws Exception {
    return exists("select to_regclass('public.' || ?) is not null", name);
  }

  private boolean exists(String sql, String name) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, name);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }
}
