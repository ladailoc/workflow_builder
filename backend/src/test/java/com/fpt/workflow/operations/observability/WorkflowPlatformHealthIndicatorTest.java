package com.fpt.workflow.operations.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkflowPlatformHealthIndicatorTest {

  @Test
  void reportsUpWhenDatabaseAndMetricsAreHealthy() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

    WorkflowPlatformHealthIndicator indicator = new WorkflowPlatformHealthIndicator(jdbc);
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("database", "UP");
    assertThat(health.getDetails()).containsEntry("deadJobs", 0L);
    assertThat(health.getDetails()).containsEntry("backlogJobs", 0L);
    assertThat(health.getDetails()).containsEntry("stalledWorkers", 0L);
    assertThat(health.getDetails()).containsEntry("failedIntegrations", 0L);
  }

  @Test
  void reportsDownWhenDatabaseConnectionFails() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT 1", Integer.class))
        .thenThrow(new DataAccessException("Connection refused") {});

    WorkflowPlatformHealthIndicator indicator = new WorkflowPlatformHealthIndicator(jdbc);
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("database", "DOWN");
    assertThat(health.getDetails()).containsKey("error");
  }

  @Test
  void reportsDownWhenPingReturnsUnexpectedValue() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(0);

    WorkflowPlatformHealthIndicator indicator = new WorkflowPlatformHealthIndicator(jdbc);
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }
}
