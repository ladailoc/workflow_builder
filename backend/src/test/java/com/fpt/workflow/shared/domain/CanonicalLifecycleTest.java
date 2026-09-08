package com.fpt.workflow.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fpt.workflow.shared.domain.lifecycle.BusinessOutcome;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.LifecycleTransitionException;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import com.fpt.workflow.shared.domain.lifecycle.TransitionGuard;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CanonicalLifecycleTest {

  @Test
  void exposesExactlyTheCanonicalLifecycleStates() {
    assertThat(WorkflowDefinitionLifecycle.values())
        .containsExactly(
            WorkflowDefinitionLifecycle.ACTIVE,
            WorkflowDefinitionLifecycle.SUSPENDED,
            WorkflowDefinitionLifecycle.ARCHIVED);
    assertThat(WorkflowVersionStatus.values())
        .containsExactly(
            WorkflowVersionStatus.DRAFT,
            WorkflowVersionStatus.PUBLISHED,
            WorkflowVersionStatus.SUPERSEDED,
            WorkflowVersionStatus.ARCHIVED);
    assertThat(TicketStatus.values())
        .containsExactly(
            TicketStatus.DRAFT,
            TicketStatus.SUBMITTED,
            TicketStatus.IN_PROGRESS,
            TicketStatus.COMPLETED,
            TicketStatus.REJECTED,
            TicketStatus.CANCELLED);
    assertThat(EventStatus.values())
        .containsExactly(
            EventStatus.CREATED,
            EventStatus.RUNNING,
            EventStatus.WAITING,
            EventStatus.COMPLETED,
            EventStatus.FAILED,
            EventStatus.CANCELLED,
            EventStatus.TERMINATED);
    assertThat(NodeExecutionStatus.values())
        .containsExactly(
            NodeExecutionStatus.CREATED,
            NodeExecutionStatus.READY,
            NodeExecutionStatus.RUNNING,
            NodeExecutionStatus.WAITING,
            NodeExecutionStatus.COMPLETED,
            NodeExecutionStatus.FAILED,
            NodeExecutionStatus.CANCELLED,
            NodeExecutionStatus.SKIPPED);
    assertThat(TaskStatus.values())
        .containsExactly(
            TaskStatus.READY,
            TaskStatus.CLAIMED,
            TaskStatus.IN_PROGRESS,
            TaskStatus.COMPLETED,
            TaskStatus.CANCELLED,
            TaskStatus.EXPIRED);
  }

  @Test
  void keepsRejectedAsTaskOutcomeInsteadOfTaskStatus() {
    assertThatThrownBy(() -> TaskStatus.valueOf("REJECTED"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(BusinessOutcome.REJECTED.value()).isEqualTo("REJECTED");
    assertThat(BusinessOutcome.of("domain_accepted").value()).isEqualTo("DOMAIN_ACCEPTED");
  }

  @Test
  void transitionGuardAppliesTheOwningDomainsAllowList() {
    EnumSet<EventStatus> allowedFromCreated =
        EnumSet.of(
            EventStatus.RUNNING,
            EventStatus.WAITING,
            EventStatus.FAILED,
            EventStatus.CANCELLED,
            EventStatus.TERMINATED);

    TransitionGuard.requireAllowed(EventStatus.CREATED, EventStatus.RUNNING, allowedFromCreated);
    assertThat(
            TransitionGuard.isAllowed(
                EventStatus.CREATED, EventStatus.COMPLETED, allowedFromCreated))
        .isFalse();
    assertThatThrownBy(
            () ->
                TransitionGuard.requireAllowed(
                    EventStatus.CREATED, EventStatus.COMPLETED, allowedFromCreated))
        .isInstanceOf(LifecycleTransitionException.class)
        .hasMessage("Transition from CREATED to COMPLETED is not allowed");
  }
}
