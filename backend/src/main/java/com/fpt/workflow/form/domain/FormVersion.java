package com.fpt.workflow.form.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "form_versions")
public class FormVersion {
  @Id private UUID id;
  @Column(name = "form_id", nullable = false) private UUID formId;
  @Column(name = "version_no", nullable = false) private int versionNo;
  @Column(nullable = false, length = 32) private String status;
  @Column(nullable = false) private long revision;
  @Column(length = 256) private String checksum;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "schema_json", nullable = false, columnDefinition = "jsonb") private JsonNode schemaJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "compiled_schema_json", columnDefinition = "jsonb") private JsonNode compiledSchemaJson;
  @Column(name = "created_by", nullable = false) private UUID createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "published_by") private UUID publishedBy;
  @Column(name = "published_at") private Instant publishedAt;
  @Version @Column(name = "lock_version", nullable = false) private long lockVersion;

  protected FormVersion() {}
  public static FormVersion draft(UUID id, UUID formId, int versionNo, UUID actor, Instant now, JsonNode schema) {
    if (versionNo < 1) throw new IllegalArgumentException("versionNo must be positive");
    FormVersion value = new FormVersion();
    value.id=id; value.formId=formId; value.versionNo=versionNo; value.status="DRAFT";
    value.createdBy=actor; value.createdAt=now; value.schemaJson=FormValues.schema(schema); return value;
  }
  public void update(long expectedRevision, JsonNode schema) {
    requireDraft(); if (revision != expectedRevision) throw new IllegalStateException("STALE_FORM_REVISION");
    schemaJson=FormValues.schema(schema); checksum=null; compiledSchemaJson=null; revision++;
  }
  public void publish(String checksum, JsonNode compiled, UUID actor, Instant now) {
    requireDraft(); if (checksum == null || checksum.isBlank()) throw new IllegalArgumentException("checksum is required");
    this.checksum=checksum; this.compiledSchemaJson=FormValues.schema(compiled); this.publishedBy=actor; this.publishedAt=now; this.status="PUBLISHED";
  }
  public void supersede() { if (!"PUBLISHED".equals(status)) throw new IllegalStateException("Only Published FormVersion can be superseded"); status="SUPERSEDED"; }
  private void requireDraft() { if (!"DRAFT".equals(status)) throw new IllegalStateException("Published FormVersion is immutable"); }
  public UUID getId(){return id;} public UUID getFormId(){return formId;} public int getVersionNo(){return versionNo;}
  public String getStatus(){return status;} public long getRevision(){return revision;} public String getChecksum(){return checksum;}
  public JsonNode getSchemaJson(){return schemaJson.deepCopy();} public JsonNode getCompiledSchemaJson(){return compiledSchemaJson==null?null:compiledSchemaJson.deepCopy();}
  public UUID getCreatedBy(){return createdBy;} public Instant getCreatedAt(){return createdAt;} public UUID getPublishedBy(){return publishedBy;}
  public Instant getPublishedAt(){return publishedAt;} public long getLockVersion(){return lockVersion;}
}
