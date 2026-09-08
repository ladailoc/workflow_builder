package com.fpt.workflow.operations.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowJobTransactions {
  private final JdbcTemplate jdbc;
  private final WorkflowJobRepository jobs;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public WorkflowJobTransactions(
      JdbcTemplate jdbc, WorkflowJobRepository jobs, UuidGenerator uuids, PlatformClock clock) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.uuids = uuids;
    this.clock = clock;
  }

  @Transactional
  public WorkflowJob enqueue(
      String type,
      String aggregateType,
      UUID aggregateId,
      JsonNode payload,
      int maxAttempts,
      Instant nextRunAt,
      String dedupKey) {
    if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
    JsonNode body = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
    if (!body.isObject()) throw new IllegalArgumentException("payload must be an object");
    Instant now = clock.now();
    UUID id = uuids.generate();
    UUID actual =
        jdbc.queryForObject(
            "INSERT INTO workflow_jobs (id,job_type,aggregate_type,aggregate_id,payload_json,status,attempts,max_attempts,next_run_at,dedup_key,created_at,updated_at) VALUES (?,?,?,?,?::jsonb,'READY',0,?,?,?,?,?) ON CONFLICT (dedup_key) DO UPDATE SET dedup_key=EXCLUDED.dedup_key RETURNING id",
            UUID.class,
            id,
            required(type, "type"),
            required(aggregateType, "aggregateType"),
            aggregateId,
            body.toString(),
            maxAttempts,
            ts(nextRunAt),
            required(dedupKey, "dedupKey"),
            ts(now),
            ts(now));
    return jobs.findById(actual).orElseThrow();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<WorkflowJob> claim(String worker, int batch, Duration lease) {
    if (batch <= 0 || lease.isNegative() || lease.isZero())
      throw new IllegalArgumentException("Invalid claim parameters");
    Instant now = clock.now(), until = now.plus(lease);
    List<UUID> ids =
        jdbc.query(
            "WITH candidates AS (SELECT id FROM workflow_jobs WHERE ((status IN ('READY','RETRY') AND next_run_at <= ?) OR (status='RUNNING' AND lease_until < ?)) ORDER BY next_run_at,created_at FOR UPDATE SKIP LOCKED LIMIT ?) UPDATE workflow_jobs j SET status='RUNNING',attempts=j.attempts+1,lease_owner=?,lease_until=?,updated_at=?,completed_at=NULL FROM candidates c WHERE j.id=c.id RETURNING j.id",
            (rs, row) -> rs.getObject(1, UUID.class),
            ts(now),
            ts(now),
            batch,
            required(worker, "worker"),
            ts(until),
            ts(now));
    return jobs.findAllById(ids);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean complete(UUID id, String worker) {
    Instant now = clock.now();
    return jdbc.update(
            "UPDATE workflow_jobs SET status='COMPLETED',lease_owner=NULL,lease_until=NULL,completed_at=?,updated_at=?,lock_version=lock_version+1 WHERE id=? AND status='RUNNING' AND lease_owner=?",
            ts(now),
            ts(now),
            id,
            worker)
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean fail(UUID id, String worker, JsonNode error, Duration delay, boolean permanent) {
    Instant now = clock.now();
    JsonNode safe =
        error == null ? JsonNodeFactory.instance.objectNode().put("code", "JOB_FAILED") : error;
    if (!safe.isObject()) throw new IllegalArgumentException("error must be an object");
    String sql =
        "UPDATE workflow_jobs SET status=CASE WHEN ? OR attempts>=max_attempts THEN 'DEAD' ELSE 'RETRY' END, next_run_at=?, lease_owner=NULL, lease_until=NULL, last_error_json=?::jsonb, completed_at=CASE WHEN ? OR attempts>=max_attempts THEN ?::timestamptz ELSE NULL::timestamptz END, updated_at=?, lock_version=lock_version+1 WHERE id=? AND status='RUNNING' AND lease_owner=?";
    return jdbc.update(
            sql,
            permanent,
            ts(now.plus(delay)),
            safe.toString(),
            permanent,
            ts(now),
            ts(now),
            id,
            worker)
        == 1;
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value.trim();
  }

  private static Timestamp ts(Instant value) {
    return Timestamp.from(value);
  }
}
