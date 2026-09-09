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
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
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
  private final EventRepository events;
  private final NodeExecutionRepository nodes;
  private final ObjectMapper objectMapper;

  public OperationalFailureService(
      WorkflowJobRepository jobs,
      OutboxEventRepository outbox,
      NotificationDispatchRepository notifications,
      IntegrationExecutionRepository integrations,
      EventRepository events,
      NodeExecutionRepository nodes,
      ObjectMapper objectMapper) {
    this.jobs = jobs;
    this.outbox = outbox;
    this.notifications = notifications;
    this.integrations = integrations;
    this.events = events;
    this.nodes = nodes;
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
                        job.getLockVersion(),
                        job.getJobType(),
                        job.getAggregateType(),
                        job.getAggregateId(),
                        "EVENT".equals(job.getAggregateType()) ? job.getAggregateId() : null,
                        "EVENT".equals(job.getAggregateType())
                            ? eventVersion(job.getAggregateId())
                            : null,
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
                        event.getLockVersion(),
                        event.getEventType(),
                        event.getAggregateType(),
                        event.getAggregateId(),
                        "EVENT".equals(event.getAggregateType()) ? event.getAggregateId() : null,
                        "EVENT".equals(event.getAggregateType())
                            ? eventVersion(event.getAggregateId())
                            : null,
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
                        dispatch.getLockVersion(),
                        dispatch.getChannel(),
                        "EVENT",
                        dispatch.getEventId(),
                        dispatch.getEventId(),
                        eventVersion(dispatch.getEventId()),
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
                          execution.getLockVersion(),
                          execution.getConnectorKey() + "/" + execution.getActionKey(),
                          "EVENT",
                          execution.getEventId(),
                          execution.getEventId(),
                          eventVersion(execution.getEventId()),
                          execution.getStatus().name(),
                          null,
                          null,
                          execution.getUpdatedAt(),
                          safeJson(execution.getSanitizedResponseJson()))));
    }
    nodes
        .findAllByStatusOrderByCreatedAtAsc(NodeExecutionStatus.FAILED)
        .forEach(execution -> failures.add(nodeFailure(execution)));
    events
        .findAllByStatusOrderByStartedAtAsc(EventStatus.FAILED)
        .forEach(event -> failures.add(eventFailure(event)));
    failures.sort(Comparator.comparing(OperationalFailure::occurredAt).reversed());
    return List.copyOf(failures);
  }

  private OperationalFailure nodeFailure(NodeExecution execution) {
    return new OperationalFailure(
        "NODE_EXECUTION",
        execution.getId(),
        execution.getLockVersion(),
        "NODE_EXECUTION",
        "EVENT",
        execution.getEventId(),
        execution.getEventId(),
        eventVersion(execution.getEventId()),
        execution.getStatus().name(),
        null,
        null,
        execution.getEndedAt() != null ? execution.getEndedAt() : execution.getCreatedAt(),
        safe(execution.getErrorJson()));
  }

  private OperationalFailure eventFailure(Event event) {
    return new OperationalFailure(
        "EVENT",
        event.getId(),
        event.getLockVersion(),
        event.getEventType().name(),
        "TICKET",
        event.getTicketId(),
        event.getId(),
        event.getLockVersion(),
        event.getStatus().name(),
        null,
        null,
        event.getEndedAt() != null ? event.getEndedAt() : event.getStartedAt(),
        objectMapper.createObjectNode().put("code", "EVENT_FAILED"));
  }

  private JsonNode safe(JsonNode value) {
    return PayloadSanitizer.sanitize(value);
  }

  private Long eventVersion(UUID eventId) {
    return events.findById(eventId).map(Event::getLockVersion).orElse(null);
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
      long lockVersion,
      String kind,
      String aggregateType,
      UUID aggregateId,
      UUID eventId,
      Long eventVersion,
      String status,
      Integer attempts,
      Integer maxAttempts,
      Instant occurredAt,
      JsonNode error) {}
}
