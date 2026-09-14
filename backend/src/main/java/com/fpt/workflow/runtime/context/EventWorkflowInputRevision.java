package com.fpt.workflow.runtime.context;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
/** Append-only WorkflowInputRevision history for one Event. Never mutated by remapping. */
@Entity @Table(name="event_workflow_input_revisions")
public class EventWorkflowInputRevision {
  @Id private UUID id;
  @Column(name="event_id",nullable=false) private UUID eventId;
  @Column(name="category_version_id",nullable=false) private UUID categoryVersionId;
  @Column(name="form_submission_id",nullable=false) private UUID formSubmissionId;
  @Column(name="ticket_revision_id") private UUID ticketRevisionId;
  @Column(name="input_revision",nullable=false) private long inputRevision;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="inputs_json",nullable=false,columnDefinition="jsonb") private JsonNode inputsJson;
  @Column(name="mapping_checksum",nullable=false,length=256) private String mappingChecksum;
  @Column(name="created_at",nullable=false) private Instant createdAt;
  protected EventWorkflowInputRevision(){}
  public static EventWorkflowInputRevision append(UUID id,UUID eventId,UUID categoryVersionId,UUID formSubmissionId,
      UUID ticketRevisionId,long inputRevision,JsonNode inputs,String mappingChecksum,Instant now){
    if(inputRevision<1)throw new IllegalArgumentException("inputRevision must be positive");
    EventWorkflowInputRevision v=new EventWorkflowInputRevision();v.id=id;v.eventId=eventId;v.categoryVersionId=categoryVersionId;
    v.formSubmissionId=formSubmissionId;v.ticketRevisionId=ticketRevisionId;v.inputRevision=inputRevision;
    v.inputsJson=inputs.deepCopy();v.mappingChecksum=mappingChecksum;v.createdAt=now;return v;
  }
  public UUID getId(){return id;} public UUID getEventId(){return eventId;} public UUID getCategoryVersionId(){return categoryVersionId;}
  public UUID getFormSubmissionId(){return formSubmissionId;} public UUID getTicketRevisionId(){return ticketRevisionId;}
  public long getInputRevision(){return inputRevision;} public JsonNode getInputsJson(){return inputsJson.deepCopy();}
  public String getMappingChecksum(){return mappingChecksum;} public Instant getCreatedAt(){return createdAt;}
}
