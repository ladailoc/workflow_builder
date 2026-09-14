package com.fpt.workflow.form.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "form_submissions")
public class FormSubmission {
  @Id private UUID id;
  @Column(name="form_version_id",nullable=false) private UUID formVersionId;
  @Column(name="context_type",nullable=false,length=32) private String contextType;
  @Column(name="context_id") private UUID contextId;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="data_json",nullable=false,columnDefinition="jsonb") private JsonNode dataJson;
  @Column(name="schema_checksum",nullable=false,length=256) private String schemaChecksum;
  @Column(name="submitted_by",nullable=false) private UUID submittedBy;
  @Column(name="submitted_at",nullable=false) private Instant submittedAt;
  protected FormSubmission() {}
  public static FormSubmission ticketCreate(UUID id, UUID formVersionId, JsonNode data, String checksum, UUID actor, Instant now) {
    FormSubmission value=new FormSubmission(); value.id=id; value.formVersionId=formVersionId; value.contextType="TICKET_CREATE";
    value.dataJson=FormValues.schema(data); value.schemaChecksum=checksum; value.submittedBy=actor; value.submittedAt=now; return value;
  }

  /** Business revision submission; the original TICKET_CREATE submission is never replaced. */
  public static FormSubmission revision(UUID id, UUID formVersionId, JsonNode data, String checksum, UUID ticketId, UUID actor, Instant now) {
    FormSubmission value=new FormSubmission(); value.id=id; value.formVersionId=formVersionId; value.contextType="REVISION_REQUEST";
    value.contextId=java.util.Objects.requireNonNull(ticketId, "ticketId");
    value.dataJson=FormValues.schema(data); value.schemaChecksum=checksum; value.submittedBy=actor; value.submittedAt=now; return value;
  }
  public void bindContext(UUID contextId){ if(this.contextId!=null) throw new IllegalStateException("FormSubmission context is immutable"); this.contextId=contextId; }
  public UUID getId(){return id;} public UUID getFormVersionId(){return formVersionId;} public String getContextType(){return contextType;}
  public UUID getContextId(){return contextId;} public JsonNode getDataJson(){return dataJson.deepCopy();} public String getSchemaChecksum(){return schemaChecksum;}
  public UUID getSubmittedBy(){return submittedBy;} public Instant getSubmittedAt(){return submittedAt;}
}
