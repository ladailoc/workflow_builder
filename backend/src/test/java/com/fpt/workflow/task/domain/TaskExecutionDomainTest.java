package com.fpt.workflow.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.LifecycleTransitionException;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskExecutionDomainTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

  @Test
  void representsRejectionAsCompletedOutcome() {
    TaskExecution task = task(UUID.randomUUID());

    task.complete(BusinessOutcome.REJECTED, NOW.plusSeconds(10));

    assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(task.getOutcome()).isEqualTo("REJECTED");
    assertThatThrownBy(() -> TaskStatus.valueOf("REJECTED"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> task.claim(UUID.randomUUID()))
        .isInstanceOf(LifecycleTransitionException.class);
  }

  @Test
  void requiresAnAssigneeBeforeWorkOrCompletion() {
    TaskExecution unassigned =
        TaskExecution.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            "Claimable review",
            null,
            null,
            OBJECT_MAPPER.createObjectNode(),
            10,
            null,
            NOW);

    assertThatThrownBy(() -> unassigned.start(NOW)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> unassigned.complete(BusinessOutcome.APPROVED, NOW))
        .isInstanceOf(IllegalStateException.class);

    UUID candidate = UUID.randomUUID();
    unassigned.claim(candidate);
    unassigned.start(NOW.plusSeconds(1));

    assertThat(unassigned.getAssigneeId()).isEqualTo(candidate);
    assertThat(unassigned.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
  }

  @Test
  void validatesImmutableSnapshotContractsAtCreation() {
    UUID eventId = UUID.randomUUID();
    UUID nodeExecutionId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                ParticipantSnapshot.create(
                    UUID.randomUUID(),
                    eventId,
                    nodeExecutionId,
                    null,
                    "ROLE",
                    "config-hash",
                    OBJECT_MAPPER.createObjectNode(),
                    ParticipantResolutionStatus.RESOLVED,
                    "EMPLOYEE",
                    null,
                    UUID.randomUUID(),
                    "APPROVER",
                    OBJECT_MAPPER.createObjectNode(),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                TaskAssignmentHistory.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    TaskAssignmentAction.REASSIGN,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    OBJECT_MAPPER.createObjectNode(),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private TaskExecution task(UUID assigneeId) {
    return TaskExecution.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        assigneeId,
        "Approval",
        "Review the request",
        OBJECT_MAPPER.createObjectNode().put("type", "object"),
        OBJECT_MAPPER.createObjectNode().put("amount", 100),
        50,
        NOW.plusSeconds(3600),
        NOW);
  }
}
