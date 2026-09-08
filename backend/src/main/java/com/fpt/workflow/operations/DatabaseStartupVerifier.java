package com.fpt.workflow.operations;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class DatabaseStartupVerifier implements ApplicationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseStartupVerifier.class);
  private static final int REQUIRED_POSTGRESQL_MAJOR = 17;

  private final JdbcTemplate jdbcTemplate;

  DatabaseStartupVerifier(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    verifyDatabase();
  }

  private void verifyDatabase() {
    Integer versionNumber =
        jdbcTemplate.queryForObject(
            "SELECT current_setting('server_version_num')::integer", Integer.class);
    String sessionTimeZone =
        jdbcTemplate.queryForObject("SELECT current_setting('TimeZone')", String.class);

    int majorVersion = Objects.requireNonNull(versionNumber) / 10_000;
    if (majorVersion != REQUIRED_POSTGRESQL_MAJOR) {
      throw new IllegalStateException(
          "Workflow Platform requires PostgreSQL 17; connected to major version " + majorVersion);
    }
    if (!"UTC".equals(sessionTimeZone)) {
      throw new IllegalStateException(
          "Workflow Platform requires UTC database sessions; connected session uses "
              + sessionTimeZone);
    }

    LOGGER.info(
        "Database startup verification passed: PostgreSQL major={}, sessionTimeZone={}",
        majorVersion,
        sessionTimeZone);
  }
}
