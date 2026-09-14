package com.fpt.workflow.ticketcategory.domain;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="ticket_category_mappings")
public class TicketCategoryMapping {
  @Id private UUID id;
  @Column(name="category_version_id",nullable=false) private UUID categoryVersionId;
  @Column(name="target_workflow_input_id",nullable=false) private UUID targetWorkflowInputId;
  @Enumerated(EnumType.STRING) @Column(name="source_type",nullable=false,length=32) private MappingSourceType sourceType;
  @Column(name="source_form_field_id") private UUID sourceFormFieldId;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="source_expression_json",columnDefinition="jsonb") private JsonNode sourceExpressionJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="constant_json",columnDefinition="jsonb") private JsonNode constantJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="default_json",columnDefinition="jsonb") private JsonNode defaultJson;
  @Column(name="on_missing",nullable=false,length=32) private String onMissing;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="transform_json",columnDefinition="jsonb") private JsonNode transformJson;
  @Column(nullable=false) private int ordinal;
  protected TicketCategoryMapping(){}
  public static TicketCategoryMapping create(UUID id,UUID categoryVersionId,UUID targetId,MappingSourceType sourceType,UUID sourceFieldId,JsonNode expression,JsonNode constant,JsonNode defaultValue,String onMissing,JsonNode transform,int ordinal){
    TicketCategoryMapping v=new TicketCategoryMapping();v.id=id;v.categoryVersionId=categoryVersionId;v.targetWorkflowInputId=targetId;v.sourceType=sourceType;v.sourceFormFieldId=sourceFieldId;
    v.sourceExpressionJson=copy(expression);v.constantJson=copy(constant);v.defaultJson=copy(defaultValue);v.onMissing=onMissing==null?"ERROR":onMissing;v.transformJson=copy(transform);v.ordinal=ordinal;return v;
  }
  private static JsonNode copy(JsonNode n){return n==null?null:n.deepCopy();}
  public UUID getId(){return id;} public UUID getCategoryVersionId(){return categoryVersionId;} public UUID getTargetWorkflowInputId(){return targetWorkflowInputId;}
  public MappingSourceType getSourceType(){return sourceType;} public UUID getSourceFormFieldId(){return sourceFormFieldId;} public JsonNode getSourceExpressionJson(){return copy(sourceExpressionJson);}
  public JsonNode getConstantJson(){return copy(constantJson);} public JsonNode getDefaultJson(){return copy(defaultJson);} public String getOnMissing(){return onMissing;}
  public JsonNode getTransformJson(){return copy(transformJson);} public int getOrdinal(){return ordinal;}
}
