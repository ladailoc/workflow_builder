package com.fpt.workflow.runtime.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.LifecycleTransitionException;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeDomainTest {

  private static final Instant STARTED_AT = Instant.parse("2026-09-07T00:00:00Z");

  @Test
  void keepsEventOutcomeSeparateAndNeverRevivesFailedEvent() {
    Event event = rootEvent();

    event.markRunning();
    event.waitFor(RuntimeWaitReason.HUMAN_TASK);
    event.markRunning();
    event.fail(STARTED_AT.plusSeconds(1));

    assertThat(event.getStatus()).isEqualTo(EventStatus.FAILED);
    assertThat(event.getOutcome()).isNull();
    assertThat(event.getEndedAt()).isEqualTo(STARTED_AT.plusSeconds(1));

    assertThatThrownBy(event::markRunning).isInstanceOf(LifecycleTransitionException.class);
    assertThatThrownBy(() -> event.cancel("RETRY", STARTED_AT.plusSeconds(2)))
        .isInstanceOf(LifecycleTransitionException.class);
  }

  @Test
  void neverRevivesATerminalNodeExecutionOccurrence() {
    NodeExecution execution = nodeExecution("activation-1", 0);

    execution.markReady();
    execution.start(STARTED_AT);
    execution.waitFor(RuntimeWaitReason.HUMAN_TASK);
    execution.start(STARTED_AT.plusMillis(500));
    assertThat(execution.getWaitReason()).isNull();
    execution.complete(
        "approved",
        JsonNodeFactory.instance.objectNode().put("decision", "APPROVED"),
        STARTED_AT.plusSeconds(1));

    assertThat(execution.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(execution.getOutcomePort()).isEqualTo("APPROVED");
    assertThat(execution.getOutputJson().path("decision").asText()).isEqualTo("APPROVED");
    assertThatThrownBy(() -> execution.start(STARTED_AT.plusSeconds(2)))
        .isInstanceOf(LifecycleTransitionException.class);
  }

  @Test
  void validatesRootChildAndSnapshotShapesWithoutEngineBehavior() {
    Event root = rootEvent();
    assertThat(root.getRootEventId()).isEqualTo(root.getId());
    assertThat(root.getEventType()).isEqualTo(EventType.ROOT);

    assertThatThrownBy(
            () ->
                NodeExecution.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "activation",
                    UUID.randomUUID(),
                    -1,
                    "path",
                    null,
                    null,
                    null,
                    JsonNodeFactory.instance.objectNode(),
                    UUID.randomUUID(),
                    STARTED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Event.createChild(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    null,
                    "SUBWORKFLOW",
                    null,
                    JsonNodeFactory.instance.objectNode(),
                    UUID.randomUUID(),
                    STARTED_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Event rootEvent() {
    UUID id = UUID.randomUUID();
    return Event.createRoot(
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        "USER_SUBMIT",
        "command-1",
        JsonNodeFactory.instance.objectNode(),
        UUID.randomUUID(),
        STARTED_AT);
  }

  private NodeExecution nodeExecution(String activationKey, int iteration) {
    return NodeExecution.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        activationKey,
        UUID.randomUUID(),
        iteration,
        "root-path",
        null,
        null,
        null,
        JsonNodeFactory.instance.objectNode(),
        UUID.randomUUID(),
        STARTED_AT);
  }
}
