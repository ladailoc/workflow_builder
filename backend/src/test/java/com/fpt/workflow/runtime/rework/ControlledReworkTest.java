package com.fpt.workflow.runtime.rework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.rework.ReworkExhaustionAction;
import com.fpt.workflow.definition.validation.ControlledCycleAnalyzer;
import com.fpt.workflow.runtime.domain.NodeExecution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlledReworkTest {

  private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void acceptsIntentionalBoundedStronglyConnectedComponent() {
    UUID version = UUID.randomUUID();
    NodeDefinition first = node(version, "review");
    NodeDefinition second = node(version, "revision");
    EdgeDefinition forward =
        edge(version, first, second, TransitionType.NORMAL, mapper.createObjectNode());
    EdgeDefinition back =
        edge(version, second, first, TransitionType.REWORK, policy(3, "CURRENT_ITEM"));

    assertThat(
            new ControlledCycleAnalyzer().analyze(List.of(first, second), List.of(forward, back)))
        .isEmpty();
  }

  @Test
  void blocksUnboundedOrUnintentionalCycles() {
    UUID version = UUID.randomUUID();
    NodeDefinition first = node(version, "first");
    NodeDefinition second = node(version, "second");
    EdgeDefinition forward =
        edge(version, first, second, TransitionType.NORMAL, mapper.createObjectNode());
    EdgeDefinition unintentional =
        edge(version, second, first, TransitionType.NORMAL, mapper.createObjectNode());
    EdgeDefinition unbounded =
        edge(version, second, first, TransitionType.REWORK, mapper.createObjectNode());

    assertThat(
            new ControlledCycleAnalyzer()
                .analyze(List.of(first, second), List.of(forward, unintentional)))
        .extracting(issue -> issue.code())
        .containsExactly("CYCLE_NOT_INTENTIONAL");
    assertThat(
            new ControlledCycleAnalyzer()
                .analyze(List.of(first, second), List.of(forward, unbounded)))
        .extracting(issue -> issue.code())
        .containsExactly("REWORK_POLICY_INVALID");
  }

  @Test
  void rejectsNormalOnlySubcycleInsideSccWithReworkEdge() {
    UUID version = UUID.randomUUID();
    NodeDefinition first = node(version, "first");
    NodeDefinition second = node(version, "second");
    NodeDefinition third = node(version, "third");
    // Normal cycle: first -> second -> first
    EdgeDefinition fwd =
        edge(version, first, second, TransitionType.NORMAL, mapper.createObjectNode());
    EdgeDefinition backNormal =
        edge(version, second, first, TransitionType.NORMAL, mapper.createObjectNode());
    // Also rework loop involving third: second -> third -> first (rework)
    EdgeDefinition toThird =
        edge(version, second, third, TransitionType.NORMAL, mapper.createObjectNode());
    EdgeDefinition reworkBack =
        edge(version, third, first, TransitionType.REWORK, policy(3, "CURRENT_ITEM"));

    // Although the SCC contains a valid rework edge (third -> first),
    // the normal cycle (first <-> second) must be rejected with CYCLE_NOT_INTENTIONAL!
    assertThat(
            new ControlledCycleAnalyzer()
                .analyze(
                    List.of(first, second, third), List.of(fwd, backNormal, toThird, reworkBack)))
        .extracting(issue -> issue.code())
        .contains("CYCLE_NOT_INTENTIONAL");
  }

  @Test
  void incrementsCycleAndIterationWithoutRevivingCompletedHistory() {
    UUID eventId = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    NodeDefinition sourceNode = node(version, "managerReview");
    NodeDefinition targetNode = node(version, "requesterRevision");
    NodeExecution source = completed(sourceNode, eventId, 1, "employee-b", null, null);
    EdgeDefinition edge =
        edge(version, sourceNode, targetNode, TransitionType.RETURN, policy(3, "CURRENT_ITEM"));

    ReworkPlan plan = new ReworkRuntimePlanner().plan(eventId, edge, source);

    assertThat(plan.exhausted()).isFalse();
    assertThat(plan.iteration()).isEqualTo(2);
    assertThat(plan.cycleId()).isNotEqualTo(source.getCycleId());
    assertThat(plan.itemToken()).isEqualTo("employee-b");
    assertThat(source.getStatus().name()).isEqualTo("COMPLETED");
  }

  @Test
  void appliesConfiguredExhaustionAndWholeNodeItemScope() {
    UUID eventId = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    NodeDefinition sourceNode = node(version, "review");
    NodeDefinition targetNode = node(version, "revision");
    EdgeDefinition whole =
        edge(version, sourceNode, targetNode, TransitionType.REWORK, policy(3, "WHOLE_NODE"));
    ReworkPlan next =
        new ReworkRuntimePlanner()
            .plan(eventId, whole, completed(sourceNode, eventId, 0, "item-1", null, null));
    EdgeDefinition bounded =
        edge(version, sourceNode, targetNode, TransitionType.REWORK, policy(1, "CURRENT_ITEM"));
    ReworkPlan exhausted =
        new ReworkRuntimePlanner()
            .plan(eventId, bounded, completed(sourceNode, eventId, 1, "item-1", null, null));

    assertThat(next.itemToken()).isNull();
    assertThat(exhausted.exhausted()).isTrue();
    assertThat(exhausted.exhaustionAction()).isEqualTo(ReworkExhaustionAction.ROUTE_PORT);
    assertThat(exhausted.exhaustionPort()).isEqualTo("REWORK_EXHAUSTED");
  }

  @Test
  void appliesFailEventExhaustion() {
    UUID eventId = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    NodeDefinition sourceNode = node(version, "review");
    NodeDefinition targetNode = node(version, "revision");
    var config = mapper.createObjectNode();
    config
        .putObject("reworkPolicy")
        .put("maxIterations", 1)
        .put("onExhausted", "FAIL_EVENT")
        .put("scope", "CURRENT_ITEM");
    EdgeDefinition bounded = edge(version, sourceNode, targetNode, TransitionType.REWORK, config);
    ReworkPlan exhausted =
        new ReworkRuntimePlanner()
            .plan(eventId, bounded, completed(sourceNode, eventId, 1, "item-1", null, null));

    assertThat(exhausted.exhausted()).isTrue();
    assertThat(exhausted.exhaustionAction()).isEqualTo(ReworkExhaustionAction.FAIL_EVENT);
    assertThat(exhausted.exhaustionPort()).isNull();
  }

  @Test
  void blocksUnsafeParallelScopeCrossing() {
    UUID eventId = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    NodeDefinition sourceNode = node(version, "review");
    NodeDefinition targetNode = node(version, "revision");
    NodeExecution source =
        completed(sourceNode, eventId, 0, "item-1", UUID.randomUUID(), UUID.randomUUID());
    EdgeDefinition edge =
        edge(version, sourceNode, targetNode, TransitionType.REWORK, policy(2, "CURRENT_ITEM"));

    assertThatThrownBy(() -> new ReworkRuntimePlanner().plan(eventId, edge, source))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REWORK_PARALLEL_SCOPE_CROSSING_UNSAFE");
  }

  private NodeDefinition node(UUID version, String key) {
    return NodeDefinition.create(
        UUID.randomUUID(),
        version,
        key,
        "REVIEW",
        key,
        null,
        1,
        mapper.createObjectNode(),
        null,
        null,
        mapper.createObjectNode().put("x", 0).put("y", 0));
  }

  private EdgeDefinition edge(
      UUID version,
      NodeDefinition source,
      NodeDefinition target,
      TransitionType type,
      com.fasterxml.jackson.databind.node.ObjectNode config) {
    return EdgeDefinition.create(
        UUID.randomUUID(),
        version,
        source.getId(),
        "RETURNED",
        target.getId(),
        null,
        0,
        false,
        type,
        null,
        config);
  }

  private com.fasterxml.jackson.databind.node.ObjectNode policy(int max, String scope) {
    var config = mapper.createObjectNode();
    config
        .putObject("reworkPolicy")
        .put("maxIterations", max)
        .put("onExhausted", "ROUTE_PORT")
        .put("exhaustionPort", "REWORK_EXHAUSTED")
        .put("scope", scope);
    return config;
  }

  private NodeExecution completed(
      NodeDefinition node,
      UUID eventId,
      int iteration,
      String itemToken,
      UUID splitScope,
      UUID joinScope) {
    NodeExecution execution =
        NodeExecution.create(
            UUID.randomUUID(),
            eventId,
            node.getId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            iteration,
            "root",
            itemToken,
            splitScope,
            joinScope,
            mapper.createObjectNode(),
            UUID.randomUUID(),
            NOW);
    execution.markReady();
    execution.start(NOW);
    execution.complete("RETURNED", mapper.createObjectNode(), NOW);
    return execution;
  }
}
