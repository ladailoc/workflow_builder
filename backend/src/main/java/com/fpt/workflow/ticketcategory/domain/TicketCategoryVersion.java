package com.fpt.workflow.ticketcategory.domain;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="ticket_category_versions")
public class TicketCategoryVersion {
  @Id private UUID id;
  @Column(name="ticket_category_id",nullable=false) private UUID ticketCategoryId;
  @Column(name="version_no",nullable=false) private int versionNo;
  @Column(nullable=false,length=32) private String status;
  @Column(name="form_version_id",nullable=false) private UUID formVersionId;
  @Column(name="workflow_version_id",nullable=false) private UUID workflowVersionId;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="creation_policy_json",nullable=false,columnDefinition="jsonb") private JsonNode creationPolicyJson;
  @Column(nullable=false) private long revision;
  @Column(length=256) private String checksum;
  @Column(name="mapping_checksum",length=256) private String mappingChecksum;
  @Column(name="created_by",nullable=false) private UUID createdBy;
  @Column(name="created_at",nullable=false) private Instant createdAt;
  @Column(name="published_by") private UUID publishedBy;
  @Column(name="published_at") private Instant publishedAt;
  @Version @Column(name="lock_version",nullable=false) private long lockVersion;
  protected TicketCategoryVersion(){}
  public static TicketCategoryVersion draft(UUID id,UUID categoryId,int versionNo,UUID formVersionId,UUID workflowVersionId,JsonNode policy,UUID actor,Instant now){
    TicketCategoryVersion v=new TicketCategoryVersion();v.id=id;v.ticketCategoryId=categoryId;v.versionNo=versionNo;v.status="DRAFT";v.formVersionId=formVersionId;v.workflowVersionId=workflowVersionId;v.creationPolicyJson=requireObject(policy);v.createdBy=actor;v.createdAt=now;return v;
  }
  public void updateBinding(long expectedRevision,UUID formVersionId,UUID workflowVersionId,JsonNode policy){requireRevision(expectedRevision);this.formVersionId=formVersionId;this.workflowVersionId=workflowVersionId;this.creationPolicyJson=requireObject(policy);mutated();}
  public void recordMappingMutation(long expectedRevision){requireRevision(expectedRevision);mutated();}
  public void publish(String checksum,String mappingChecksum,UUID actor,Instant now){requireDraft();this.checksum=requireText(checksum);this.mappingChecksum=requireText(mappingChecksum);this.publishedBy=actor;this.publishedAt=now;this.status="PUBLISHED";}
  public void supersede(){if(!"PUBLISHED".equals(status))throw new IllegalStateException("Only Published CategoryVersion can be superseded");status="SUPERSEDED";}
  private void requireDraft(){if(!"DRAFT".equals(status))throw new IllegalStateException("Published CategoryVersion is immutable");}
  private void requireRevision(long expectedRevision){requireDraft();if(revision!=expectedRevision)throw new IllegalStateException("STALE_CATEGORY_REVISION");}
  private void mutated(){revision++;checksum=null;mappingChecksum=null;}
  private static JsonNode requireObject(JsonNode n){if(n==null||!n.isObject())throw new IllegalArgumentException("creationPolicy must be an object");return n.deepCopy();}
  private static String requireText(String s){if(s==null||s.isBlank())throw new IllegalArgumentException("checksum is required");return s;}
  public UUID getId(){return id;} public UUID getTicketCategoryId(){return ticketCategoryId;} public int getVersionNo(){return versionNo;} public String getStatus(){return status;}
  public UUID getFormVersionId(){return formVersionId;} public UUID getWorkflowVersionId(){return workflowVersionId;} public JsonNode getCreationPolicyJson(){return creationPolicyJson.deepCopy();}
  public long getRevision(){return revision;} public String getChecksum(){return checksum;} public String getMappingChecksum(){return mappingChecksum;} public UUID getCreatedBy(){return createdBy;}
  public Instant getCreatedAt(){return createdAt;} public UUID getPublishedBy(){return publishedBy;} public Instant getPublishedAt(){return publishedAt;} public long getLockVersion(){return lockVersion;}
}
