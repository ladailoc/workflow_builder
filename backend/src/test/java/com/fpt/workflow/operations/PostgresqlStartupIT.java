package com.fpt.workflow.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostgresqlStartupIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_platform_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private DataSource dataSource;

  @Test
  void connectsToPostgresql17InUtcWithFlywayBaseline() {
    Integer majorVersion =
        jdbcTemplate.queryForObject(
            "SELECT current_setting('server_version_num')::integer / 10000", Integer.class);
    String sessionTimeZone =
        jdbcTemplate.queryForObject("SELECT current_setting('TimeZone')", String.class);
    Integer appliedBaseline =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history " + "WHERE version = '1' AND success",
            Integer.class);

    assertThat(majorVersion).isEqualTo(17);
    assertThat(sessionTimeZone).isEqualTo("UTC");
    assertThat(appliedBaseline).isEqualTo(1);
  }

  @Test
  void usesConfiguredHikariPool() {
    assertThat(dataSource).isInstanceOf(HikariDataSource.class);

    HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    assertThat(hikariDataSource.getPoolName()).isEqualTo("WorkflowPlatformTestPool");
    assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(4);
    assertThat(hikariDataSource.getConnectionInitSql()).isEqualTo("SET TIME ZONE 'UTC'");
  }
}
