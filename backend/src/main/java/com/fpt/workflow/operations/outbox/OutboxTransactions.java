package com.fpt.workflow.operations.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OutboxTransactions {
  private final JdbcTemplate jdbc;
  private final OutboxEventRepository events;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public OutboxTransactions(
      JdbcTemplate jdbc, OutboxEventRepository events, UuidGenerator uuids, PlatformClock clock) {
    this.jdbc = jdbc;
    this.events = events;
    this.uuids = uuids;
    this.clock = clock;
  }

  @Transactional
  public OutboxEvent enqueue(
      String type,
      String aggregateType,
      UUID aggregateId,
      JsonNode payload,
      int maxAttempts,
      String dedupKey) {
    JsonNode body = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
    if (!body.isObject()) throw new IllegalArgumentException("payload must be an object");
    if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
    Instant now = clock.now();
    UUID id = uuids.generate();
    UUID actual =
        jdbc.queryForObject(
            "INSERT INTO outbox_events (id,event_type,aggregate_type,aggregate_id,payload_json,dedup_key,status,created_at,attempts,max_attempts,next_run_at) VALUES (?,?,?,?,?::jsonb,?,'READY',?,0,?,?) ON CONFLICT (dedup_key) DO UPDATE SET dedup_key=EXCLUDED.dedup_key RETURNING id",
            UUID.class,
            id,
            required(type),
            required(aggregateType),
            aggregateId,
            body.toString(),
            required(dedupKey),
            ts(now),
            maxAttempts,
            ts(now));
    return events.findById(actual).orElseThrow();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<OutboxEvent> claim(String worker, int batch, Duration lease) {
    Instant now = clock.now();
    Instant until = now.plus(lease);
    List<UUID> ids =
        jdbc.query(
            "WITH candidates AS (SELECT id FROM outbox_events WHERE ((status IN ('READY','RETRY') AND next_run_at<=?) OR (status='PUBLISHING' AND lease_until<?)) ORDER BY next_run_at,created_at FOR UPDATE SKIP LOCKED LIMIT ?) UPDATE outbox_events o SET status='PUBLISHING',attempts=o.attempts+1,lease_owner=?,lease_until=? FROM candidates c WHERE o.id=c.id RETURNING o.id",
            (rs, row) -> rs.getObject(1, UUID.class),
            ts(now),
            ts(now),
            batch,
            required(worker),
            ts(until));
    return events.findAllById(ids);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean published(UUID id, String worker) {
    Instant now = clock.now();
    return jdbc.update(
            "UPDATE outbox_events SET status='PUBLISHED',published_at=?,lease_owner=NULL,lease_until=NULL,lock_version=lock_version+1 WHERE id=? AND status='PUBLISHING' AND lease_owner=?",
            ts(now),
            id,
            worker)
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean failed(UUID id, String worker, JsonNode error, Duration delay) {
    Instant now = clock.now();
    JsonNode safe = error == null ? JsonNodeFactory.instance.objectNode() : error;
    return jdbc.update(
            "UPDATE outbox_events SET status=CASE WHEN attempts>=max_attempts THEN 'DEAD' ELSE 'RETRY' END,next_run_at=?,last_error_json=?::jsonb,lease_owner=NULL,lease_until=NULL WHERE id=? AND status='PUBLISHING' AND lease_owner=?",
            ts(now.plus(delay)),
            safe.toString(),
            id,
            worker)
        == 1;
  }

  private static String required(String v) {
    if (v == null || v.isBlank()) throw new IllegalArgumentException("value is required");
    return v.trim();
  }

  private static java.sql.Timestamp ts(Instant value) {
    return java.sql.Timestamp.from(value);
  }
}
