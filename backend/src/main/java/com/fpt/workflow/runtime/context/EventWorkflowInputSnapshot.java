package com.fpt.workflow.runtime.context;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="event_workflow_input_snapshots")
public class EventWorkflowInputSnapshot {
  @Id @Column(name="event_id") private UUID eventId;
  @Column(name="category_version_id",nullable=false) private UUID categoryVersionId;
  /** The exact workflow selected for the Event, which may be a tenant override. */
  @Column(name="workflow_version_id") private UUID workflowVersionId;
  @Column(name="form_submission_id",nullable=false) private UUID formSubmissionId;
  @Column(name="input_revision",nullable=false) private long inputRevision;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="inputs_json",nullable=false,columnDefinition="jsonb") private JsonNode inputsJson;
  @Column(name="mapping_checksum",nullable=false,length=256) private String mappingChecksum;
  @Column(name="created_at",nullable=false) private Instant createdAt;
  protected EventWorkflowInputSnapshot(){}
  public static EventWorkflowInputSnapshot create(UUID eventId,UUID categoryVersionId,UUID workflowVersionId,UUID formSubmissionId,JsonNode inputs,String checksum,Instant now){
    EventWorkflowInputSnapshot v=new EventWorkflowInputSnapshot();v.eventId=eventId;v.categoryVersionId=categoryVersionId;v.workflowVersionId=workflowVersionId;v.formSubmissionId=formSubmissionId;v.inputRevision=1;v.inputsJson=inputs.deepCopy();v.mappingChecksum=checksum;v.createdAt=now;return v;
  }
  /** Compatibility factory for legacy callers; the workflow is recovered from the Event row when available. */
  public static EventWorkflowInputSnapshot create(UUID eventId,UUID categoryVersionId,UUID formSubmissionId,JsonNode inputs,String checksum,Instant now){
    return create(eventId,categoryVersionId,null,formSubmissionId,inputs,checksum,now);
  }
  public UUID getEventId(){return eventId;} public UUID getCategoryVersionId(){return categoryVersionId;} public UUID getFormSubmissionId(){return formSubmissionId;}
  public UUID getWorkflowVersionId(){return workflowVersionId;}
  public long getInputRevision(){return inputRevision;} public JsonNode getInputsJson(){return inputsJson.deepCopy();} public String getMappingChecksum(){return mappingChecksum;} public Instant getCreatedAt(){return createdAt;}
}
