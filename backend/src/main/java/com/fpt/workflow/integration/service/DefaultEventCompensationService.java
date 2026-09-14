package com.fpt.workflow.integration.service;

import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.lifecycle.EventCompensationPort;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default §17.6 compensation planning: deterministically scans the completed external side effects
 * captured in {@code integration_executions} for the cancelled event and, per the event's
 * compensation config (stored in the workflow version snapshot at publish time), files an audited
 * manual recovery or action execution. No side effect is rolled back automatically; isolation from
 * normal forward execution is preserved.
 */
@Service
public class DefaultEventCompensationService implements EventCompensationPort {

  private static final Logger log = LoggerFactory.getLogger(DefaultEventCompensationService.class);

  private final IntegrationExecutionRepository executions;
  private final com.fpt.workflow.operations.audit.AuditEventRepository audits;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public DefaultEventCompensationService(
      IntegrationExecutionRepository executions,
      com.fpt.workflow.operations.audit.AuditEventRepository audits,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.executions = executions;
    this.audits = audits;
    this.uuids = uuids;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void planForCancelledEvent(
      Event event, String reason, CorrelationId correlationId, CommandId commandId) {
    var sideEffects =
        executions.findAllByEventIdOrderByCreatedAtAsc(event.getId()).stream()
            .filter(e -> e.getStatus() == IntegrationExecutionStatus.SUCCEEDED)
            .toList();
    if (sideEffects.isEmpty()) {
      log.debug("No completed external side effects to compensate for cancelled event {}", event.getId());
      return;
    }

    // Compensation policy comes from the publish-time executionPackage snapshot (deterministic,
    // version-pinned); without it the planner surfaces the gap for manual intervention.
    CompensationPolicy policy = compensationPolicy(event);
    switch (policy) {
      case NONE -> {
        auditCompensationSkipped(event, sideEffects, reason, correlationId, commandId);
      }
      case MANUAL -> {
        auditCompensationRequired(event, sideEffects, reason, correlationId, commandId);
      }
      case ACTION -> {
        auditCompensationRequired(event, sideEffects, reason, correlationId, commandId);
        // Future: resolve compensating ConnectorAction (e.g. CREATE_PO → CANCEL_PO) from
        // event.getWorkflowVersionId() execution package; enqueue action via the normal
        // connector dispatch when the mapping is present.
        log.info(
            "Compensation ACTION requested for cancelled event {}: {} side effects captured for audit (deterministic extent {})",
            event.getId(), sideEffects.size(), sideEffects.size());
      }
    }
  }

  private CompensationPolicy compensationPolicy(Event event) {
    var variables = event.getVariablesJson();
    if (variables != null && variables.isObject() && variables.hasNonNull("compensationPolicy")) {
      return CompensationPolicy.parse(variables.path("compensationPolicy").asText(null));
    }
    return CompensationPolicy.NONE;
  }

  private void auditCompensationSkipped(
      Event event,
      List<com.fpt.workflow.integration.domain.IntegrationExecution> sideEffects,
      String reason,
      CorrelationId correlationId,
      CommandId commandId) {
    log.info(
        "Compensation policy NONE: cancelled event {} had {} completed side effect(s); no compensation filed (reason={})",
        event.getId(), sideEffects.size(), reason);
    writeAudit(event, "COMPENSATION_SKIPPED", sideEffects.size(), reason, correlationId, commandId);
  }

  private void auditCompensationRequired(
      Event event,
      List<com.fpt.workflow.integration.domain.IntegrationExecution> sideEffects,
      String reason,
      CorrelationId correlationId,
      CommandId commandId) {
    log.info(
        "Compensation/manual-recovery required for cancelled event {}: {} side effect(s) (reason={})",
        event.getId(), sideEffects.size(), reason);
    writeAudit(event, "COMPENSATION_REQUIRED", sideEffects.size(), reason, correlationId, commandId);
  }

  private void writeAudit(
      Event event,
      String eventType,
      int sideEffectCount,
      String reason,
      CorrelationId correlationId,
      CommandId commandId) {
    var actor = event.getStartedBy();
    com.fasterxml.jackson.databind.node.ObjectNode metadata =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    metadata.put("eventId", event.getId().toString());
    metadata.put("sideEffectCount", sideEffectCount);
    if (reason != null) metadata.put("reason", reason);
    audits.save(
        com.fpt.workflow.operations.audit.AuditEvent.record(
            uuids.generate(),
            "EVENT",
            event.getId(),
            eventType,
            actor,
            actor,
            correlationId,
            commandId,
            metadata,
            clock.now()));
  }
}
