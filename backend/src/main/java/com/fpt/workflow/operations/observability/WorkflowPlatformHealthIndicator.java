package com.fpt.workflow.operations.observability;

import java.util.Objects;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Health indicator for core Workflow Platform runtime components: - Database connectivity - Durable
 * outbox dead & backlog jobs - Stalled worker leases - Integration executions status
 */
@Component("workflowPlatform")
public class WorkflowPlatformHealthIndicator implements HealthIndicator {

  private final JdbcTemplate jdbcTemplate;

  public WorkflowPlatformHealthIndicator(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Health health() {
    try {
      // 1. Database connectivity probe
      Integer ping = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
      if (ping == null || ping != 1) {
        return Health.down().withDetail("database", "Unexpected response from SELECT 1").build();
      }

      // 2. Durable outbox health
      Long deadJobs = queryCount("SELECT count(*) FROM workflow_jobs WHERE status='DEAD'");
      Long backlogJobs =
          queryCount("SELECT count(*) FROM workflow_jobs WHERE status in ('READY', 'RETRY')");

      // 3. Worker stalled leases probe (lease expired by > 2 minutes)
      Long stalledWorkers =
          queryCount(
              "SELECT count(*) FROM workflow_jobs WHERE status='RUNNING' AND lease_until < (now() - interval '2 minutes')");

      // 4. Integration executions health
      Long failedIntegrations =
          queryCount(
              "SELECT count(*) FROM integration_executions WHERE status in ('FAILED', 'MANUAL_RECONCILIATION')");

      return Health.up()
          .withDetail("database", "UP")
          .withDetail("deadJobs", deadJobs)
          .withDetail("backlogJobs", backlogJobs)
          .withDetail("stalledWorkers", stalledWorkers)
          .withDetail("failedIntegrations", failedIntegrations)
          .build();
    } catch (Exception ex) {
      return Health.down(ex)
          .withDetail("database", "DOWN")
          .withDetail("error", ex.getMessage())
          .build();
    }
  }

  private Long queryCount(String sql) {
    try {
      Long result = jdbcTemplate.queryForObject(sql, Long.class);
      return Objects.requireNonNullElse(result, 0L);
    } catch (Exception ignored) {
      return 0L;
    }
  }
}
