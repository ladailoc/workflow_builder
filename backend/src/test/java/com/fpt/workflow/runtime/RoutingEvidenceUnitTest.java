package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.monitoring.EventMonitoringService;
import com.fpt.workflow.runtime.routing.domain.RoutingDecision;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** P2-11 (§12.5): evaluated_edges_json carries structured route evidence, not only edge IDs. */
class RoutingEvidenceUnitTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void routeViewExposesEvaluatedEdgesEvidence() {
    UUID edgeId = UUID.randomUUID();
    com.fasterxml.jackson.databind.node.ArrayNode evaluated = mapper.createArrayNode();
    evaluated
        .addObject()
        .put("edgeId", edgeId.toString())
        .put("priority", 0)
        .put("defaultTransition", true)
        .put("conditionPresent", false)
        .put("selected", true);
    RoutingDecision decision =
        RoutingDecision.record(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DEFAULT", "SINGLE_BY_PORT",
            evaluated, mapper.createArrayNode().add(edgeId.toString()), Instant.EPOCH);

    EventMonitoringService.RouteView view = RouteViewFactory.from(decision);
    assertThat(view.evaluatedEdges().toString()).contains(edgeId.toString());
    assertThat(view.evaluatedEdgesView())
        .hasSize(1)
        .allSatisfy(
            row -> {
              assertThat(row.edgeId()).isEqualTo(edgeId.toString());
              assertThat(row.selected()).isTrue();
              assertThat(row.matched()).isNull();
            });
  }

  @Test
  void evaluatedErrorEvidence_neverContainsExpressionSource() {
    com.fasterxml.jackson.databind.node.ArrayNode evaluated = mapper.createArrayNode();
    evaluated
        .addObject()
        .put("edgeId", UUID.randomUUID().toString())
        .put("priority", 1)
        .put("defaultTransition", false)
        .put("conditionPresent", true)
        .put("selected", false)
        .putObject("evaluationError")
        .put("type", "IllegalStateException");
    UUID source = UUID.randomUUID();
    RoutingDecision decision =
        RoutingDecision.record(
            UUID.randomUUID(), UUID.randomUUID(), source, "DEFAULT", "EXCLUSIVE_CONDITIONAL",
            evaluated, mapper.createArrayNode(), Instant.EPOCH);
    List<EventMonitoringService.EvaluatedEdgeView> rows =
        RouteViewFactory.from(decision).evaluatedEdgesView();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).evaluationError()).isEqualTo("IllegalStateException");
    assertThat(rows.get(0).selected()).isFalse();
  }

  @Test
  void legacyEdgeIdOnlyArrays_stillParse() throws Exception {
    // Backward compatibility: old decisions persisted edge strings only (v2.3 migration path).
    UUID edgeId = UUID.randomUUID();
    com.fasterxml.jackson.databind.JsonNode legacy =
        mapper.createArrayNode().add(edgeId.toString());
    RoutingDecision decision =
        RoutingDecision.record(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DEFAULT", "SINGLE_BY_PORT",
            legacy, mapper.createArrayNode().add(edgeId.toString()), Instant.EPOCH);
    List<EventMonitoringService.EvaluatedEdgeView> rows =
        RouteViewFactory.from(decision).evaluatedEdgesView();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).edgeId()).isNull(); // legacy rows lack structured keys; safe parse.
  }

  // Reflection helper: RouteView.from is package-visible through EventMonitoringService.
  private static final class RouteViewFactory {
    static EventMonitoringService.RouteView from(RoutingDecision decision) {
      try {
        Method m =
            EventMonitoringService.RouteView.class.getDeclaredMethod(
                "from", RoutingDecision.class);
        m.setAccessible(true);
        return (EventMonitoringService.RouteView) m.invoke(null, decision);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }
}
