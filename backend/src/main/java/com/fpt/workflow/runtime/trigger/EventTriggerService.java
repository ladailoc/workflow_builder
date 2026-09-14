package com.fpt.workflow.runtime.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.shared.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional root-event ingestion with replay semantics for external correlation keys. */
@Service
public class EventTriggerService {

  private final JdbcTemplate jdbc;
  private final EventRepository events;
  private final UuidGenerator uuids;

  public EventTriggerService(JdbcTemplate jdbc, EventRepository events, UuidGenerator uuids) {
    this.jdbc = jdbc;
    this.events = events;
    this.uuids = uuids;
  }

  @Transactional
  public TriggerResult createRoot(
      UUID ticketId,
      UUID workflowVersionId,
      UUID startedTicketRevisionId,
      UUID previousEventId,
      UUID restartedFromEventId,
      String triggerType,
      String triggerCorrelationKey,
      JsonNode variables,
      UUID startedBy,
      Instant startedAt) {
    Objects.requireNonNull(triggerType, "triggerType");
    String correlation = normalize(triggerCorrelationKey);
    if (correlation != null) {
      // Serialize contenders on the exact logical trigger before checking/inserting. The V40
      // unique index remains the independent invariant for any caller that bypasses this service.
      String normalizedType = triggerType.trim().toUpperCase(java.util.Locale.ROOT);
      String lockKey = normalizedType.length() + ":" + normalizedType + correlation;
      jdbc.query(
          "select pg_advisory_xact_lock(hashtextextended(?, 0))",
          resultSet -> null,
          lockKey);
      Optional<UUID> existingId = existingId(triggerType, correlation);
      if (existingId.isPresent()) {
        return new TriggerResult(events.findById(existingId.orElseThrow()).orElseThrow(), false);
      }
    }

    Event created =
        Event.createRoot(
            uuids.generate(),
            ticketId,
            workflowVersionId,
            startedTicketRevisionId,
            previousEventId,
            restartedFromEventId,
            triggerType,
            correlation,
            variables,
            startedBy,
            startedAt);
    return new TriggerResult(events.saveAndFlush(created), true);
  }

  private Optional<UUID> existingId(String triggerType, String correlation) {
    return jdbc.query(
        "select id from events where trigger_type = ? and trigger_correlation_key = ?",
        resultSet ->
            resultSet.next()
                ? Optional.of(resultSet.getObject("id", UUID.class))
                : Optional.empty(),
        triggerType.trim().toUpperCase(java.util.Locale.ROOT),
        correlation);
  }

  private String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record TriggerResult(Event event, boolean created) {}
}
