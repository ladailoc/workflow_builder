package com.fpt.workflow.ticket.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.shared.domain.lifecycle.LifecycleTransitionException;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TicketDomainTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-07T00:00:00Z");

  @Test
  void advancesOnlySequentialImmutableBusinessSnapshots() {
    Ticket ticket = draft();
    UUID firstRevisionId = UUID.randomUUID();

    ticket.submit(
        firstRevisionId,
        1,
        JsonNodeFactory.instance.objectNode().put("amount", 100),
        CREATED_AT.plusSeconds(1));

    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.SUBMITTED);
    assertThat(ticket.getDataRevision()).isEqualTo(1);
    assertThat(ticket.getCurrentRevisionId()).isEqualTo(firstRevisionId);
    assertThat(ticket.getSubmittedAt()).isEqualTo(CREATED_AT.plusSeconds(1));

    UUID secondRevisionId = UUID.randomUUID();
    ticket.recordBusinessRevision(
        secondRevisionId,
        2,
        JsonNodeFactory.instance.objectNode().put("amount", 120),
        CREATED_AT.plusSeconds(2));

    assertThat(ticket.getDataRevision()).isEqualTo(2);
    assertThat(ticket.getCurrentRevisionId()).isEqualTo(secondRevisionId);
    assertThat(ticket.getDataJson().path("amount").asInt()).isEqualTo(120);
  }

  @Test
  void rejectsInvalidStateRevisionNumberAndNonObjectData() {
    Ticket ticket = draft();

    assertThatThrownBy(
            () ->
                ticket.recordBusinessRevision(
                    UUID.randomUUID(),
                    1,
                    JsonNodeFactory.instance.objectNode(),
                    CREATED_AT.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                ticket.submit(
                    UUID.randomUUID(),
                    2,
                    JsonNodeFactory.instance.objectNode(),
                    CREATED_AT.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Ticket.createDraft(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    JsonNodeFactory.instance.arrayNode(),
                    CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class);

    ticket.submit(
        UUID.randomUUID(), 1, JsonNodeFactory.instance.objectNode(), CREATED_AT.plusSeconds(1));
    assertThatThrownBy(
            () ->
                ticket.submit(
                    UUID.randomUUID(),
                    2,
                    JsonNodeFactory.instance.objectNode(),
                    CREATED_AT.plusSeconds(2)))
        .isInstanceOf(LifecycleTransitionException.class);
    assertThatThrownBy(
            () ->
                ticket.updateDraft(
                    JsonNodeFactory.instance.objectNode(), CREATED_AT.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void handlesLifecycleTransitionsAndEnforcesGuards() {
    Ticket ticket = draft();
    Instant now = CREATED_AT.plusSeconds(1);

    // Cannot markInProgress or complete/reject from DRAFT directly
    assertThatThrownBy(() -> ticket.markInProgress(now))
        .isInstanceOf(LifecycleTransitionException.class);
    assertThatThrownBy(() -> ticket.complete(now))
        .isInstanceOf(LifecycleTransitionException.class);
    assertThatThrownBy(() -> ticket.reject(now))
        .isInstanceOf(LifecycleTransitionException.class);

    // Cancel from DRAFT is allowed
    ticket.cancel(now);
    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CANCELLED);
    assertThat(ticket.getCompletedAt()).isEqualTo(now);
    assertThat(ticket.getSubmittedAt()).isEqualTo(now);

    // Cannot transition from terminal CANCELLED
    assertThatThrownBy(() -> ticket.markInProgress(now.plusSeconds(1)))
        .isInstanceOf(LifecycleTransitionException.class);

    // Normal happy path: DRAFT -> SUBMITTED -> IN_PROGRESS -> COMPLETED
    Ticket normalTicket = draft();
    normalTicket.submit(UUID.randomUUID(), 1, JsonNodeFactory.instance.objectNode(), now);
    assertThat(normalTicket.getStatus()).isEqualTo(TicketStatus.SUBMITTED);

    normalTicket.markInProgress(now.plusSeconds(2));
    assertThat(normalTicket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);

    normalTicket.complete(now.plusSeconds(3));
    assertThat(normalTicket.getStatus()).isEqualTo(TicketStatus.COMPLETED);
    assertThat(normalTicket.getCompletedAt()).isEqualTo(now.plusSeconds(3));

    // Rejection path: DRAFT -> SUBMITTED -> IN_PROGRESS -> REJECTED
    Ticket rejectTicket = draft();
    rejectTicket.submit(UUID.randomUUID(), 1, JsonNodeFactory.instance.objectNode(), now);
    rejectTicket.markInProgress(now.plusSeconds(2));
    rejectTicket.reject(now.plusSeconds(3));
    assertThat(rejectTicket.getStatus()).isEqualTo(TicketStatus.REJECTED);
    assertThat(rejectTicket.getCompletedAt()).isEqualTo(now.plusSeconds(3));
  }

  @Test
  void normalizesBusinessSubjectIdentityWithoutParticipantSemantics() {
    UUID subjectId = UUID.randomUUID();
    TicketSubject subject =
        TicketSubject.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "employee",
            subjectId,
            "evaluation_target",
            "evaluationTargets",
            CREATED_AT);

    assertThat(subject.getSubjectType()).isEqualTo("EMPLOYEE");
    assertThat(subject.getRoleKey()).isEqualTo("EVALUATION_TARGET");
    assertThat(subject.getSubjectRefId()).isEqualTo(subjectId);
    assertThat(subject.getSourceField()).isEqualTo("evaluationTargets");
  }

  private Ticket draft() {
    return Ticket.createDraft(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        JsonNodeFactory.instance.objectNode().put("amount", 80),
        CREATED_AT);
  }
}
