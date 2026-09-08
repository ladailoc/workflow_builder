package com.fpt.workflow.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.integration.service.PayloadSanitizer;
import com.fpt.workflow.operations.job.WorkflowJobRepository;
import com.fpt.workflow.operations.job.WorkflowJobStatus;
import com.fpt.workflow.operations.outbox.OutboxEventRepository;
import com.fpt.workflow.operations.outbox.OutboxStatus;
import com.fpt.workflow.slanotification.domain.NotificationDispatchStatus;
import com.fpt.workflow.slanotification.repository.NotificationDispatchRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalFailureService {
  private final WorkflowJobRepository jobs;
  private final OutboxEventRepository outbox;
  private final NotificationDispatchRepository notifications;
  private final IntegrationExecutionRepository integrations;
  private final ObjectMapper objectMapper;

  public OperationalFailureService(
      WorkflowJobRepository jobs,
      OutboxEventRepository outbox,
      NotificationDispatchRepository notifications,
      IntegrationExecutionRepository integrations,
      ObjectMapper objectMapper) {
    this.jobs = jobs;
    this.outbox = outbox;
    this.notifications = notifications;
    this.integrations = integrations;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<OperationalFailure> list() {
    List<OperationalFailure> failures = new ArrayList<>();
    jobs.findAllByStatusOrderByUpdatedAtAsc(WorkflowJobStatus.DEAD)
        .forEach(
            job ->
                failures.add(
                    new OperationalFailure(
                        "WORKFLOW_JOB",
                        job.getId(),
                        job.getJobType(),
                        job.getAggregateType(),
                        job.getAggregateId(),
                        job.getStatus().name(),
                        job.getAttempts(),
                        job.getMaxAttempts(),
                        job.getUpdatedAt(),
                        safe(job.getLastErrorJson()))));
    outbox
        .findAllByStatusOrderByCreatedAtAsc(OutboxStatus.DEAD)
        .forEach(
            event ->
                failures.add(
                    new OperationalFailure(
                        "OUTBOX_EVENT",
                        event.getId(),
                        event.getEventType(),
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getStatus().name(),
                        event.getAttempts(),
                        event.getMaxAttempts(),
                        event.getCreatedAt(),
                        safe(event.getLastErrorJson()))));
    notifications
        .findAllByStatusOrderByUpdatedAtAsc(NotificationDispatchStatus.DEAD)
        .forEach(
            dispatch ->
                failures.add(
                    new OperationalFailure(
                        "NOTIFICATION_DISPATCH",
                        dispatch.getId(),
                        dispatch.getChannel(),
                        "EVENT",
                        dispatch.getEventId(),
                        dispatch.getStatus().name(),
                        dispatch.getAttempts(),
                        null,
                        dispatch.getUpdatedAt(),
                        safe(dispatch.getLastErrorJson()))));
    for (IntegrationExecutionStatus status :
        List.of(
            IntegrationExecutionStatus.FAILED, IntegrationExecutionStatus.MANUAL_RECONCILIATION)) {
      integrations
          .findAllByStatusOrderByUpdatedAtAsc(status)
          .forEach(
              execution ->
                  failures.add(
                      new OperationalFailure(
                          "INTEGRATION_EXECUTION",
                          execution.getId(),
                          execution.getConnectorKey() + "/" + execution.getActionKey(),
                          "EVENT",
                          execution.getEventId(),
                          execution.getStatus().name(),
                          null,
                          null,
                          execution.getUpdatedAt(),
                          safeJson(execution.getSanitizedResponseJson()))));
    }
    failures.sort(Comparator.comparing(OperationalFailure::occurredAt).reversed());
    return List.copyOf(failures);
  }

  private JsonNode safe(JsonNode value) {
    return PayloadSanitizer.sanitize(value);
  }

  private JsonNode safeJson(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return safe(objectMapper.readTree(value));
    } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
      return null;
    }
  }

  public record OperationalFailure(
      String category,
      UUID id,
      String kind,
      String aggregateType,
      UUID aggregateId,
      String status,
      Integer attempts,
      Integer maxAttempts,
      Instant occurredAt,
      JsonNode error) {}
}
