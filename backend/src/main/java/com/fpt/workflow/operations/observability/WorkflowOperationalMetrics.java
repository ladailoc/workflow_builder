package com.fpt.workflow.operations.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database-backed gauges survive process restarts and describe the authoritative runtime. */
@Component
public class WorkflowOperationalMetrics implements MeterBinder {

  private final JdbcTemplate jdbc;

  public WorkflowOperationalMetrics(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    // Event starts, completions, failures
    count(registry, "workflow.starts", "select count(*) from events");
    count(registry, "workflow.completions", "select count(*) from events where status='COMPLETED'");
    count(registry, "workflow.failures", "select count(*) from events where status='FAILED'");

    // Node execution duration
    number(
        registry,
        "workflow.node.duration.seconds",
        "select coalesce(avg(extract(epoch from (ended_at-started_at))),0) "
            + "from node_executions where ended_at is not null and started_at is not null");

    // Task SLA & processing duration
    count(
        registry,
        "workflow.task.sla.overdue",
        "select count(*) from task_executions where due_at < now() "
            + "and status in ('READY','CLAIMED','IN_PROGRESS')");
    number(
        registry,
        "workflow.task.duration.seconds",
        "select coalesce(avg(extract(epoch from (completed_at-created_at))),0) "
            + "from task_executions where completed_at is not null");

    // Routing failures
    count(
        registry,
        "workflow.routing.failures",
        "select count(*) from audit_events where event_type='ROUTING_FAILED'");

    // Join synchronization waiting & completion
    count(
        registry,
        "workflow.join.waiting",
        "select count(*) from join_states where status='WAITING'");
    count(
        registry,
        "workflow.join.completion",
        "select count(*) from join_states where status='COMPLETED'");

    // Integration calls, latency, failures, retries
    count(registry, "workflow.integration.calls", "select count(*) from integration_executions");
    number(
        registry,
        "workflow.integration.latency.seconds",
        "select coalesce(avg(extract(epoch from (completed_at-created_at))),0) "
            + "from integration_executions where completed_at is not null");
    count(
        registry,
        "workflow.integration.failures",
        "select count(*) from integration_executions where status in ('FAILED','MANUAL_RECONCILIATION')");
    count(
        registry,
        "workflow.integration.retries",
        "select count(*) from integration_attempts where attempt_number > 1");

    // Callback status: late & duplicate
    count(
        registry,
        "workflow.callback.late",
        "select count(*) from integration_callbacks where status='LATE'");
    count(
        registry,
        "workflow.callback.duplicate",
        "select count(*) from integration_callbacks where status='DUPLICATE'");

    // Durable jobs: ready, running, retry, dead, worker lease recovery/stalled
    count(
        registry, "workflow.jobs.ready", "select count(*) from workflow_jobs where status='READY'");
    count(
        registry,
        "workflow.jobs.running",
        "select count(*) from workflow_jobs where status='RUNNING'");
    count(
        registry, "workflow.jobs.retry", "select count(*) from workflow_jobs where status='RETRY'");
    count(registry, "workflow.jobs.dead", "select count(*) from workflow_jobs where status='DEAD'");
    count(
        registry,
        "workflow.jobs.lease.stalled",
        "select count(*) from workflow_jobs where status='RUNNING' and lease_until < now()");
    count(
        registry,
        "workflow.worker.stalled",
        "select count(*) from workflow_jobs where status='RUNNING' and lease_until < (now() - interval '2 minutes')");
  }

  private void count(MeterRegistry registry, String name, String sql) {
    number(registry, name, sql);
  }

  private void number(MeterRegistry registry, String name, String sql) {
    Supplier<Number> value =
        () -> Objects.requireNonNullElse(jdbc.queryForObject(sql, Double.class), 0D);
    Gauge.builder(name, value, supplier -> supplier.get().doubleValue()).register(registry);
  }
}
