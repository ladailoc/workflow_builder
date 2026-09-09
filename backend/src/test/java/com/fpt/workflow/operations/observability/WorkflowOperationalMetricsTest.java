package com.fpt.workflow.operations.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkflowOperationalMetricsTest {

  @Test
  void registersAllP3OperationalSignalsFromAuthoritativePersistence() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(2D);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new WorkflowOperationalMetrics(jdbc).bindTo(registry);

    assertThat(registry.get("workflow.starts").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.completions").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.failures").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.node.duration.seconds").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.task.sla.overdue").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.task.duration.seconds").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.routing.failures").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.join.waiting").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.join.completion").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.integration.calls").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.integration.latency.seconds").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.integration.failures").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.integration.retries").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.callback.late").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.callback.duplicate").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.jobs.ready").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.jobs.running").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.jobs.retry").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.jobs.dead").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.jobs.lease.stalled").gauge().value()).isEqualTo(2D);
    assertThat(registry.get("workflow.worker.stalled").gauge().value()).isEqualTo(2D);

    // Verify all registered metrics have no high-cardinality or sensitive tags
    registry
        .getMeters()
        .forEach(
            meter -> {
              assertThat(meter.getId().getTags())
                  .noneMatch(
                      tag ->
                          tag.getKey().contains("ticket")
                              || tag.getKey().contains("event")
                              || tag.getKey().contains("user")
                              || tag.getKey().contains("payload"));
            });
  }

  @Test
  void handlesNullQueryResultsSafely() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(null);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new WorkflowOperationalMetrics(jdbc).bindTo(registry);

    assertThat(registry.get("workflow.starts").gauge().value()).isEqualTo(0D);
    assertThat(registry.get("workflow.node.duration.seconds").gauge().value()).isEqualTo(0D);
  }
}
