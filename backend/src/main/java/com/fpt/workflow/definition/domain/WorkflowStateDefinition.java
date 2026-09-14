package com.fpt.workflow.definition.domain;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="workflow_states")
public class WorkflowStateDefinition {
  @Id private UUID id;
  @Column(name="workflow_version_id",nullable=false) private UUID workflowVersionId;
  @Column(name="state_key",nullable=false,length=128) private String stateKey;
  @Column(nullable=false,length=200) private String name;
  @Column private String description;
  @Column(name="state_group",length=128) private String stateGroup;
  @Column(nullable=false) private boolean terminal;
  @Column(name="display_order",nullable=false) private int displayOrder;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="metadata_json",nullable=false,columnDefinition="jsonb") private JsonNode metadataJson;
  protected WorkflowStateDefinition(){}
  public static WorkflowStateDefinition create(UUID id,UUID versionId,String key,String name,String description,String group,boolean terminal,int order,JsonNode metadata){
    WorkflowStateDefinition v=new WorkflowStateDefinition();v.id=id;v.workflowVersionId=versionId;v.stateKey=DefinitionValues.key(key).toUpperCase(java.util.Locale.ROOT);
    v.name=DefinitionValues.requiredText(name,"name");v.description=description;v.stateGroup=group;v.terminal=terminal;v.displayOrder=order;
    v.metadataJson=DefinitionValues.object(metadata,"metadataJson");return v;
  }
  public UUID getId(){return id;} public UUID getWorkflowVersionId(){return workflowVersionId;} public String getStateKey(){return stateKey;}
  public String getName(){return name;} public String getDescription(){return description;} public String getStateGroup(){return stateGroup;}
  public boolean isTerminal(){return terminal;} public int getDisplayOrder(){return displayOrder;} public JsonNode getMetadataJson(){return metadataJson.deepCopy();}
}
