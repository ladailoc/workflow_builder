package com.fpt.workflow.ticketcategory.domain;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="ticket_categories")
public class TicketCategory {
  @Id private UUID id;
  @Column(nullable=false,unique=true,length=128) private String key;
  @Column(nullable=false,length=200) private String name;
  @Column private String description;
  @Column(name="category_group",length=100) private String categoryGroup;
  @Column(length=256) private String icon;
  @Column(nullable=false,length=32) private String lifecycle;
  @Column(name="current_published_version_id") private UUID currentPublishedVersionId;
  @Column(name="active_draft_version_id") private UUID activeDraftVersionId;
  @Column(name="created_by",nullable=false) private UUID createdBy;
  @Column(name="created_at",nullable=false) private Instant createdAt;
  @Column(name="updated_at",nullable=false) private Instant updatedAt;
  @Version @Column(name="lock_version",nullable=false) private long lockVersion;
  protected TicketCategory(){}
  public static TicketCategory create(UUID id,String key,String name,String description,String group,String icon,UUID actor,Instant now){
    TicketCategory v=new TicketCategory();v.id=id;v.key=requireKey(key);v.name=requireText(name);v.description=description;v.categoryGroup=group;v.icon=icon;v.lifecycle="ACTIVE";v.createdBy=actor;v.createdAt=now;v.updatedAt=now;return v;
  }
  public void pointToDraft(UUID id,Instant now){activeDraftVersionId=id;updatedAt=now;}
  public void pointToPublished(UUID id,Instant now){currentPublishedVersionId=id;activeDraftVersionId=null;updatedAt=now;}
  private static String requireKey(String v){if(v==null||!v.matches("[A-Za-z][A-Za-z0-9._-]{0,127}"))throw new IllegalArgumentException("Invalid categoryKey");return v;}
  private static String requireText(String v){if(v==null||v.isBlank())throw new IllegalArgumentException("name is required");return v.trim();}
  public UUID getId(){return id;} public String getKey(){return key;} public String getName(){return name;} public String getDescription(){return description;}
  public String getCategoryGroup(){return categoryGroup;} public String getIcon(){return icon;} public String getLifecycle(){return lifecycle;}
  public UUID getCurrentPublishedVersionId(){return currentPublishedVersionId;} public UUID getActiveDraftVersionId(){return activeDraftVersionId;}
  public UUID getCreatedBy(){return createdBy;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;} public long getLockVersion(){return lockVersion;}
}
