package com.fpt.workflow.rework.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "revision_requested_fields")
public class RevisionRequestedField {
  @Id private UUID id;

  @Column(name = "revision_request_id", nullable = false)
  private UUID revisionRequestId;

  @Column(name = "field_key", nullable = false, length = 128)
  private String fieldKey;

  @Column(nullable = false)
  private String label;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private RuntimeRequestedFieldType type;

  @Column(nullable = false)
  private boolean required;

  @Column(nullable = false)
  private int ordinal;

  @Column(nullable = false)
  private boolean sensitive;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "schema_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode schemaJson;

  protected RevisionRequestedField() {}

  public static RevisionRequestedField create(
      UUID id,
      UUID requestId,
      String key,
      String label,
      RuntimeRequestedFieldType type,
      boolean required,
      int ordinal,
      boolean sensitive,
      JsonNode schema) {
    if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
    if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_]{0,127}"))
      throw new IllegalArgumentException("Invalid field key");
    if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
    if (schema == null || !schema.isObject())
      throw new IllegalArgumentException("schema must be an object");
    RevisionRequestedField value = new RevisionRequestedField();
    value.id = Objects.requireNonNull(id);
    value.revisionRequestId = Objects.requireNonNull(requestId);
    value.fieldKey = key;
    value.label = label.trim();
    value.type = Objects.requireNonNull(type);
    value.required = required;
    value.ordinal = ordinal;
    value.sensitive = sensitive;
    value.schemaJson = schema.deepCopy();
    return value;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRevisionRequestId() {
    return revisionRequestId;
  }

  public String getFieldKey() {
    return fieldKey;
  }

  public String getLabel() {
    return label;
  }

  public RuntimeRequestedFieldType getType() {
    return type;
  }

  public boolean isRequired() {
    return required;
  }

  public int getOrdinal() {
    return ordinal;
  }

  public boolean isSensitive() {
    return sensitive;
  }

  public JsonNode getSchemaJson() {
    return schemaJson.deepCopy();
  }
}
