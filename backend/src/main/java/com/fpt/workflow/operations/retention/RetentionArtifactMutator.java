package com.fpt.workflow.operations.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetentionArtifactMutator {

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  private record AttemptPayload(UUID id, JsonNode request, JsonNode response) {}

  public RetentionArtifactMutator(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public boolean hasLegalHold(RetentionCategory category, String aggregateType, UUID aggregateId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM retention_legal_holds WHERE category=? AND aggregate_type=? AND aggregate_id=?",
            Integer.class,
            category.name(),
            key(aggregateType, "aggregateType"),
            aggregateId);
    return count != null && count > 0;
  }

  public boolean mutate(
      RetentionCategory category,
      String aggregateType,
      UUID aggregateId,
      RetentionDecision decision,
      Instant now) {
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(decision, "decision");
    if (decision == RetentionDecision.RETAIN) {
      return false;
    }
    String normalizedType = key(aggregateType, "aggregateType");
    jdbc.queryForObject("SELECT set_config('app.retention_redaction','on', true)", String.class);
    return switch (category) {
      case AUDIT -> mutateAudit(normalizedType, aggregateId, decision);
      case EVENT -> mutateEvent(normalizedType, aggregateId, decision);
      case TASK_SUBMISSION -> mutateTaskDecision(normalizedType, aggregateId, decision);
      case INTEGRATION_PAYLOAD -> mutateIntegration(normalizedType, aggregateId, decision, now);
      case ATTACHMENT -> mutateFile(normalizedType, aggregateId, decision, now);
      case NOTIFICATION -> mutateNotification(normalizedType, aggregateId, decision, now);
    };
  }

  private boolean mutateAudit(String aggregateType, UUID aggregateId, RetentionDecision decision) {
    requireType(aggregateType, "AUDIT_EVENT");
    JsonNode metadata = one("SELECT metadata_json FROM audit_events WHERE id=?", aggregateId);
    if (metadata == null) {
      return false;
    }
    return jdbc.update(
            "UPDATE audit_events SET metadata_json=?::jsonb WHERE id=?",
            redactObject(metadata, decision).toString(),
            aggregateId)
        == 1;
  }

  private boolean mutateEvent(String aggregateType, UUID aggregateId, RetentionDecision decision) {
    requireType(aggregateType, "EVENT");
    JsonNode variables = one("SELECT variables_json FROM events WHERE id=?", aggregateId);
    if (variables == null) {
      return false;
    }
    return jdbc.update(
            "UPDATE events SET variables_json=?::jsonb WHERE id=?",
            redactObject(variables, decision).toString(),
            aggregateId)
        == 1;
  }

  private boolean mutateTaskDecision(
      String aggregateType, UUID aggregateId, RetentionDecision decision) {
    requireType(aggregateType, "TASK_DECISION");
    JsonNode formData = one("SELECT form_data_json FROM task_decisions WHERE id=?", aggregateId);
    if (formData == null) {
      return false;
    }
    return jdbc.update(
            "UPDATE task_decisions SET form_data_json=?::jsonb WHERE id=?",
            redactObject(formData, decision).toString(),
            aggregateId)
        == 1;
  }

  private boolean mutateIntegration(
      String aggregateType, UUID aggregateId, RetentionDecision decision, Instant now) {
    requireType(aggregateType, "INTEGRATION_EXECUTION");
    boolean payloadRemoved = decision == RetentionDecision.HARD_DELETE;
    int executions =
        jdbc.update(
            "UPDATE integration_executions SET sanitized_request_json=CASE WHEN ? THEN NULL ELSE ?::jsonb END, sanitized_response_json=CASE WHEN ? THEN NULL ELSE ?::jsonb END, updated_at=?, lock_version=lock_version+1 WHERE id=?",
            payloadRemoved,
            jsonOrNull(
                one("SELECT sanitized_request_json FROM integration_executions WHERE id=?", aggregateId),
                decision),
            payloadRemoved,
            jsonOrNull(
                one(
                    "SELECT sanitized_response_json FROM integration_executions WHERE id=?",
                    aggregateId),
                decision),
            Timestamp.from(now),
            aggregateId);
    for (AttemptPayload attempt : attemptPayloads(aggregateId)) {
      jdbc.update(
          "UPDATE integration_attempts SET sanitized_request_json=CASE WHEN ? THEN NULL ELSE ?::jsonb END, sanitized_response_json=CASE WHEN ? THEN NULL ELSE ?::jsonb END WHERE id=?",
          payloadRemoved,
          jsonOrNull(attempt.request(), decision),
          payloadRemoved,
          jsonOrNull(attempt.response(), decision),
          attempt.id());
    }
    return executions == 1;
  }

  private boolean mutateFile(
      String aggregateType, UUID aggregateId, RetentionDecision decision, Instant now) {
    requireType(aggregateType, "FILE");
    JsonNode metadata = one("SELECT metadata_json FROM files WHERE id=?", aggregateId);
    if (metadata == null) {
      return false;
    }
    ObjectNode redacted = redactObject(metadata, decision);
    redacted.put("retentionRedactedAt", now.toString());
    return jdbc.update(
            "UPDATE files SET original_name=?, metadata_json=?::jsonb, lock_version=lock_version+1 WHERE id=?",
            decision == RetentionDecision.MASK ? "***MASKED***" : "retained-file",
            redacted.toString(),
            aggregateId)
        == 1;
  }

  private boolean mutateNotification(
      String aggregateType, UUID aggregateId, RetentionDecision decision, Instant now) {
    requireType(aggregateType, "NOTIFICATION_DISPATCH");
    JsonNode recipient =
        one("SELECT recipient_snapshot_json FROM notification_dispatches WHERE id=?", aggregateId);
    if (recipient == null) {
      return false;
    }
    JsonNode template =
        one("SELECT template_snapshot_json FROM notification_dispatches WHERE id=?", aggregateId);
    JsonNode payload = one("SELECT payload_json FROM notification_dispatches WHERE id=?", aggregateId);
    JsonNode error =
        one("SELECT last_error_json FROM notification_dispatches WHERE id=?", aggregateId);
    return jdbc.update(
            "UPDATE notification_dispatches SET recipient_snapshot_json=?::jsonb, template_snapshot_json=?::jsonb, payload_json=?::jsonb, last_error_json=?::jsonb, updated_at=?, lock_version=lock_version+1 WHERE id=?",
            redactObject(recipient, decision).toString(),
            redactObject(template, decision).toString(),
            redactObject(payload, decision).toString(),
            error == null ? null : redactObject(error, decision).toString(),
            Timestamp.from(now),
            aggregateId)
        == 1;
  }

  private JsonNode one(String sql, UUID id) {
    return jdbc.query(
        sql,
        rs -> {
          if (!rs.next()) {
            return null;
          }
          return readJson(rs.getString(1));
        },
        id);
  }

  private List<AttemptPayload> attemptPayloads(UUID integrationExecutionId) {
    return jdbc.query(
        "SELECT id, sanitized_request_json, sanitized_response_json FROM integration_attempts WHERE integration_execution_id=?",
        (rs, rowNum) ->
            new AttemptPayload(
                (UUID) rs.getObject("id"),
                readJson(rs.getString("sanitized_request_json")),
                readJson(rs.getString("sanitized_response_json"))),
        integrationExecutionId);
  }

  private JsonNode readJson(String value) {
    if (value == null) {
      return null;
    }
    try {
      return mapper.readTree(value);
    } catch (Exception ex) {
      throw new IllegalStateException("Stored JSON payload is not readable", ex);
    }
  }

  private String jsonOrNull(JsonNode value, RetentionDecision decision) {
    if (value == null || decision == RetentionDecision.HARD_DELETE) {
      return null;
    }
    return redact(value, decision).toString();
  }

  private static ObjectNode redactObject(JsonNode value, RetentionDecision decision) {
    JsonNode redacted = redact(value == null ? JSON.objectNode() : value, decision);
    return redacted.isObject() ? (ObjectNode) redacted : JSON.objectNode();
  }

  private static JsonNode redact(JsonNode value, RetentionDecision decision) {
    if (decision == RetentionDecision.HARD_DELETE) {
      return value != null && value.isArray() ? JSON.arrayNode() : JSON.objectNode();
    }
    if (value == null || value.isNull()) {
      return JSON.nullNode();
    }
    if (value.isObject()) {
      ObjectNode out = JSON.objectNode();
      value.fields()
          .forEachRemaining(entry -> out.set(entry.getKey(), redact(entry.getValue(), decision)));
      return out;
    }
    if (value.isArray()) {
      ArrayNode out = JSON.arrayNode();
      value.forEach(child -> out.add(redact(child, decision)));
      return out;
    }
    if (value.isTextual()) {
      return JSON.textNode(
          decision == RetentionDecision.MASK ? "***MASKED***" : "***ANONYMIZED***");
    }
    if (decision == RetentionDecision.ANONYMIZE) {
      return JSON.nullNode();
    }
    return value.deepCopy();
  }

  private static String key(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static void requireType(String actual, String expected) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(
          "Unsupported retention aggregate type " + actual + " for " + expected);
    }
  }
}
