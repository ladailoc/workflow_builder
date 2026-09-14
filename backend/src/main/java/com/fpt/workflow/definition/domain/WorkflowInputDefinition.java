package com.fpt.workflow.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="workflow_inputs")
public class WorkflowInputDefinition {
  @Id private UUID id;
  @Column(name="workflow_version_id",nullable=false) private UUID workflowVersionId;
  @Column(name="input_key",nullable=false,length=128) private String inputKey;
  @Column(name="semantic_tag",length=128) private String semanticTag;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="type_json",nullable=false,columnDefinition="jsonb") private TypeDescriptor type;
  @Column(nullable=false) private boolean required;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="default_json",columnDefinition="jsonb") private JsonNode defaultJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="schema_json",columnDefinition="jsonb") private JsonNode schemaJson;
  @Column(nullable=false) private boolean sensitive;
  @Column private String description;
  @Column(nullable=false) private int ordinal;
  protected WorkflowInputDefinition() {}
  public static WorkflowInputDefinition create(UUID id,UUID versionId,String key,String semanticTag,TypeDescriptor type,boolean required,JsonNode defaultJson,JsonNode schemaJson,boolean sensitive,String description,int ordinal){
    WorkflowInputDefinition v=new WorkflowInputDefinition();v.id=id;v.workflowVersionId=versionId;v.inputKey=DefinitionValues.key(key);v.semanticTag=semanticTag;
    v.type=type;v.required=required;v.defaultJson=defaultJson==null?null:defaultJson.deepCopy();v.schemaJson=schemaJson==null?null:schemaJson.deepCopy();v.sensitive=sensitive;v.description=description;v.ordinal=ordinal;return v;
  }
  public UUID getId(){return id;} public UUID getWorkflowVersionId(){return workflowVersionId;} public String getInputKey(){return inputKey;}
  public String getSemanticTag(){return semanticTag;} public TypeDescriptor getType(){return type;} public boolean isRequired(){return required;}
  public JsonNode getDefaultJson(){return defaultJson==null?null:defaultJson.deepCopy();} public JsonNode getSchemaJson(){return schemaJson==null?null:schemaJson.deepCopy();}
  public boolean isSensitive(){return sensitive;} public String getDescription(){return description;} public int getOrdinal(){return ordinal;}
}
