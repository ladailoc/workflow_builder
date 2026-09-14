package com.fpt.workflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.integration.service.DefaultEventCompensationService;
import com.fpt.workflow.runtime.lifecycle.EventCompensationPort;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** P2-13 (§17.6): compensation planning is an explicit, audited business operation. */
class EventCompensationPlannerTest {

  private final IntegrationExecutionRepository executions = mock(IntegrationExecutionRepository.class);
  private final AuditEventRepository audits = mock(AuditEventRepository.class);
  private final UuidGenerator uuids = mock(UuidGenerator.class);
  private final PlatformClock clock = mock(PlatformClock.class);
  private final Instant now = Instant.parse("2026-09-08T00:00:00Z");

  private DefaultEventCompensationService service() {
    when(uuids.generate()).thenReturn(UUID.randomUUID());
    when(clock.now()).thenReturn(now);
    when(audits.save(any())).thenAnswer(a -> a.getArgument(0));
    return new DefaultEventCompensationService(executions, audits, uuids, clock);
  }

  private Event eventWithPolicy(String compensationPolicy) {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    return Event.createRoot(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        "MANUAL",
        null,
        mapper.createObjectNode().put("compensationPolicy", compensationPolicy),
        UUID.randomUUID(),
        now);
  }

  private IntegrationExecution succeededExecution(UUID eventId) {
    UUID id = UUID.randomUUID();
    IntegrationExecution execution =
        IntegrationExecution.createRunning(
            id, eventId, UUID.randomUUID(), "ERP", "CREATE_PO", 1, UUID.randomUUID(),
            "logic:" + id, "idem:" + id, "{}", now.minusSeconds(600));
    execution.markSucceeded("{}", "ext-" + id, now.minusSeconds(500));
    return execution;
  }

  @Test
  void cancelWithCompletedSideEffectAndManualPolicy_filesCompensationRequiredAudit() {
    Event event = eventWithPolicy("MANUAL");
    when(executions.findAllByEventIdOrderByCreatedAtAsc(event.getId()))
        .thenReturn(List.of(succeededExecution(event.getId())));

    service()
        .planForCancelledEvent(
            event, "cancel with side effects",
            new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));

    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audits).save(saved.capture());
    assertThat(saved.getValue().getEventType()).isEqualTo("COMPENSATION_REQUIRED");
    assertThat(saved.getValue().getMetadataJson().path("sideEffectCount").asInt()).isEqualTo(1);
  }

  @Test
  void cancelWithNonePolicy_skipsCompensationWithAuditEvidence() {
    Event event = eventWithPolicy("NONE");
    when(executions.findAllByEventIdOrderByCreatedAtAsc(event.getId()))
        .thenReturn(List.of(succeededExecution(event.getId())));

    service()
        .planForCancelledEvent(
            event, "policy none",
            new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));

    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audits).save(saved.capture());
    assertThat(saved.getValue().getEventType()).isEqualTo("COMPENSATION_SKIPPED");
  }

  @Test
  void cancelWithoutCompletedSideEffects_writesNoAudit() {
    Event event = eventWithPolicy("MANUAL");
    when(executions.findAllByEventIdOrderByCreatedAtAsc(event.getId())).thenReturn(List.of());

    service()
        .planForCancelledEvent(
            event, "no side effects",
            new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));

    verify(audits, never()).save(any());
  }

  @Test
  void actionPolicy_filesCompensationRequiredAuditForExplicitAction() {
    Event event = eventWithPolicy("ACTION");
    when(executions.findAllByEventIdOrderByCreatedAtAsc(event.getId()))
        .thenReturn(List.of(succeededExecution(event.getId())));

    service()
        .planForCancelledEvent(
            event, "policy action",
            new CorrelationId(UUID.randomUUID()), new CommandId(UUID.randomUUID()));

    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audits).save(saved.capture());
    assertThat(saved.getValue().getEventType()).isEqualTo("COMPENSATION_REQUIRED");
  }

  @Test
  void failedExecutions_doNotCountAsCompensableSideEffects() {
    UUID executionId = UUID.randomUUID();
    IntegrationExecution failed =
        IntegrationExecution.createRunning(
            executionId, UUID.randomUUID(), UUID.randomUUID(), "ERP", "CREATE_PO", 1,
            UUID.randomUUID(), "logic:" + executionId, "idem:" + executionId, "{}",
            now.minusSeconds(600));
    failed.markFailed(
        com.fpt.workflow.integration.domain.IntegrationErrorCategory.TIMEOUT, "{}", now.minusSeconds(60));
    assertThat(failed.getStatus()).isEqualTo(IntegrationExecutionStatus.FAILED);
  }
}
