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
