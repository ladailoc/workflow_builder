package com.fpt.workflow.slanotification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.operations.job.WorkflowJobTransactions;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.slanotification.domain.NotificationDispatch;
import com.fpt.workflow.slanotification.repository.NotificationDispatchRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class NotificationTransactions {
  private static final Set<EventStatus> TERMINAL =
      EnumSet.of(
          EventStatus.COMPLETED, EventStatus.FAILED, EventStatus.CANCELLED, EventStatus.TERMINATED);
  private final JdbcTemplate jdbc;
  private final NotificationDispatchRepository dispatches;
  private final EventRepository events;
  private final WorkflowJobTransactions jobs;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public NotificationTransactions(
      JdbcTemplate jdbc,
      NotificationDispatchRepository dispatches,
      EventRepository events,
      WorkflowJobTransactions jobs,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.jdbc = jdbc;
    this.dispatches = dispatches;
    this.events = events;
    this.jobs = jobs;
    this.uuids = uuids;
    this.clock = clock;
  }

  @Transactional
  public NotificationDispatch create(
      UUID eventId,
      UUID nodeId,
      UUID taskId,
      String channel,
      UUID recipient,
      JsonNode recipientSnapshot,
      JsonNode template,
      JsonNode payload,
      String dedupKey,
      int maxAttempts,
      boolean allowAfterTerminal) {
    Instant now = clock.now();
    UUID id = uuids.generate();
    UUID actual =
        jdbc.queryForObject(
            "INSERT INTO notification_dispatches (id,event_id,node_execution_id,task_id,channel,recipient_user_id,recipient_snapshot_json,template_snapshot_json,payload_json,dedup_key,status,attempts,allow_after_terminal,created_at,updated_at) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,'READY',0,?,?,?) ON CONFLICT(dedup_key) DO UPDATE SET dedup_key=EXCLUDED.dedup_key RETURNING id",
            UUID.class,
            id,
            eventId,
            nodeId,
            taskId,
            channel,
            recipient,
            recipientSnapshot.toString(),
            template.toString(),
            payload.toString(),
            dedupKey,
            allowAfterTerminal,
            Timestamp.from(now),
            Timestamp.from(now));
    var jobPayload =
        new com.fasterxml.jackson.databind.node.ObjectNode(
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
    jobPayload.put("dispatchId", actual.toString());
    jobs.enqueue(
        "NOTIFICATION_DELIVERY",
        "NOTIFICATION_DISPATCH",
        actual,
        jobPayload,
        maxAttempts,
        now,
        "notification-job:" + actual);
    return dispatches.findById(actual).orElseThrow();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<NotificationDispatch> begin(UUID id) {
    NotificationDispatch d = dispatches.findByIdForUpdate(id).orElseThrow();
    if (d.getStatus() == com.fpt.workflow.slanotification.domain.NotificationDispatchStatus.SENT
        || d.getStatus() == com.fpt.workflow.slanotification.domain.NotificationDispatchStatus.DEAD
        || d.getStatus()
            == com.fpt.workflow.slanotification.domain.NotificationDispatchStatus.CANCELLED)
      return Optional.empty();
    if (d.getStatus()
        == com.fpt.workflow.slanotification.domain.NotificationDispatchStatus.SENDING) {
      return Optional.of(d);
    }
    var event = events.findById(d.getEventId()).orElseThrow();
    if (TERMINAL.contains(event.getStatus()) && !d.isAllowAfterTerminal()) {
      d.cancel(clock.now());
      dispatches.saveAndFlush(d);
      return Optional.empty();
    }
    d.begin(clock.now());
    return Optional.of(dispatches.saveAndFlush(d));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void sent(UUID id) {
    NotificationDispatch d = dispatches.findByIdForUpdate(id).orElseThrow();
    if (d.getStatus() == com.fpt.workflow.slanotification.domain.NotificationDispatchStatus.SENT)
      return;
    d.sent(clock.now());
    dispatches.saveAndFlush(d);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(UUID id, JsonNode error, boolean dead) {
    NotificationDispatch d = dispatches.findByIdForUpdate(id).orElseThrow();
    d.failed(error, dead, clock.now());
    dispatches.saveAndFlush(d);
  }
}
