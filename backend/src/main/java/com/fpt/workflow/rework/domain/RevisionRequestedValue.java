package com.fpt.workflow.rework.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "revision_requested_values")
public class RevisionRequestedValue {
  @Id
  @Column(name = "requested_field_id")
  private UUID requestedFieldId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "value_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode valueJson;

  @Column(name = "submitted_by", nullable = false)
  private UUID submittedBy;

  @Column(name = "submitted_at", nullable = false, columnDefinition = "timestamptz")
  private Instant submittedAt;

  protected RevisionRequestedValue() {}

  public static RevisionRequestedValue create(
      UUID fieldId, JsonNode value, UUID actor, Instant at) {
    if (value == null)
      throw new IllegalArgumentException("value is required; use JSON null explicitly");
    RevisionRequestedValue result = new RevisionRequestedValue();
    result.requestedFieldId = Objects.requireNonNull(fieldId);
    result.valueJson = value.deepCopy();
    result.submittedBy = Objects.requireNonNull(actor);
    result.submittedAt = Objects.requireNonNull(at);
    return result;
  }

  public UUID getRequestedFieldId() {
    return requestedFieldId;
  }

  public JsonNode getValueJson() {
    return valueJson.deepCopy();
  }

  public UUID getSubmittedBy() {
    return submittedBy;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }
}
