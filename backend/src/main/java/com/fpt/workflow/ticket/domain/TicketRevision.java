package com.fpt.workflow.ticket.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ticket_revisions")
public class TicketRevision {

  @Id private UUID id;

  @Column(name = "ticket_id", nullable = false)
  private UUID ticketId;

  @Column(name = "revision_no", nullable = false)
  private long revisionNo;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "data_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode dataSnapshotJson;

  @Column(name = "source_schema_version", nullable = false, length = 128)
  private String sourceSchemaVersion;

  @Column(name = "schema_checksum", nullable = false, length = 256)
  private String schemaChecksum;

  @Column(name = "submitted_by", nullable = false)
  private UUID submittedBy;

  @Column(name = "submitted_at", nullable = false, columnDefinition = "timestamptz")
  private Instant submittedAt;

  @Column(name = "change_reason")
  private String changeReason;

  protected TicketRevision() {}

  private TicketRevision(
      UUID id,
      UUID ticketId,
      long revisionNo,
      JsonNode dataSnapshotJson,
      String sourceSchemaVersion,
      String schemaChecksum,
      UUID submittedBy,
      Instant submittedAt,
      String changeReason) {
    if (revisionNo <= 0) {
      throw new IllegalArgumentException("revisionNo must be positive");
    }
    this.id = Objects.requireNonNull(id, "id");
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.revisionNo = revisionNo;
    this.dataSnapshotJson = TicketValues.dataObject(dataSnapshotJson, "dataSnapshotJson");
    this.sourceSchemaVersion =
        TicketValues.requiredText(sourceSchemaVersion, "sourceSchemaVersion");
    this.schemaChecksum = TicketValues.requiredText(schemaChecksum, "schemaChecksum");
    this.submittedBy = Objects.requireNonNull(submittedBy, "submittedBy");
    this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
    this.changeReason = TicketValues.optionalText(changeReason);
  }

  public static TicketRevision create(
      UUID id,
      UUID ticketId,
      long revisionNo,
      JsonNode dataSnapshotJson,
      String sourceSchemaVersion,
      String schemaChecksum,
      UUID submittedBy,
      Instant submittedAt,
      String changeReason) {
    return new TicketRevision(
        id,
        ticketId,
        revisionNo,
        dataSnapshotJson,
        sourceSchemaVersion,
        schemaChecksum,
        submittedBy,
        submittedAt,
        changeReason);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public long getRevisionNo() {
    return revisionNo;
  }

  public JsonNode getDataSnapshotJson() {
    return dataSnapshotJson.deepCopy();
  }

  public String getSourceSchemaVersion() {
    return sourceSchemaVersion;
  }

  public String getSchemaChecksum() {
    return schemaChecksum;
  }

  public UUID getSubmittedBy() {
    return submittedBy;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public String getChangeReason() {
    return changeReason;
  }
}
