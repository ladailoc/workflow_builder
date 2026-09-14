package com.fpt.workflow.ticketcategory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.WorkflowInputDefinition;
import com.fpt.workflow.definition.repository.WorkflowInputDefinitionRepository;
import com.fpt.workflow.form.domain.FormField;
import com.fpt.workflow.form.repository.FormFieldRepository;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryMapping;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryMappingRepository;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryRepository;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryVersionRepository;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CategoryMappingService {
  private static final Set<String> SYSTEM_PATHS=Set.of("actor.id","actor.userId","category.key");
  private final TicketCategoryMappingRepository mappings;
  private final WorkflowInputDefinitionRepository inputs;
  private final FormFieldRepository fields;
  private final TicketCategoryVersionRepository categoryVersions;
  private final TicketCategoryRepository categories;
  public CategoryMappingService(TicketCategoryMappingRepository mappings,WorkflowInputDefinitionRepository inputs,FormFieldRepository fields,TicketCategoryVersionRepository categoryVersions,TicketCategoryRepository categories){this.mappings=mappings;this.inputs=inputs;this.fields=fields;this.categoryVersions=categoryVersions;this.categories=categories;}

  public ObjectNode resolveInputs(UUID categoryVersionId,UUID workflowVersionId,JsonNode formData,UUID actorId,String categoryKey){
    UUID formVersionId=categoryVersions.findById(categoryVersionId)
        .orElseThrow(()->new UnprocessableCommandException("CATEGORY_VERSION_NOT_FOUND","Published CategoryVersion was not found"))
        .getFormVersionId();
    return resolveInputs(categoryVersionId,workflowVersionId,formVersionId,formData,actorId,categoryKey);
  }

  public ObjectNode resolveInputs(UUID categoryVersionId,UUID workflowVersionId,UUID formVersionId,JsonNode formData,UUID actorId,String categoryKey){
    Map<UUID,WorkflowInputDefinition> byId=new LinkedHashMap<>();
    inputs.findAllByWorkflowVersionIdOrderByOrdinalAsc(workflowVersionId).forEach(i->byId.put(i.getId(),i));
    Map<UUID,FormField> formFields=new HashMap<>(); fields.findAllByFormVersionIdOrderByOrdinalAsc(formVersionId).forEach(f->formFields.put(f.getId(),f));
    Map<UUID,TicketCategoryMapping> byTarget=new HashMap<>();
    mappings.findAllByCategoryVersionIdOrderByOrdinalAsc(categoryVersionId).forEach(m->byTarget.put(m.getTargetWorkflowInputId(),m));
    ObjectNode result=JsonNodeFactory.instance.objectNode();
    for(WorkflowInputDefinition input:byId.values()){
      TicketCategoryMapping mapping=byTarget.get(input.getId());
      JsonNode value=mapping==null?null:resolve(mapping,formFields,formData,actorId,categoryKey);
      if(missing(value)&&mapping!=null&&mapping.getDefaultJson()!=null)value=mapping.getDefaultJson();
      if(missing(value)&&input.getDefaultJson()!=null)value=input.getDefaultJson();
      if(missing(value)){
        if(input.isRequired())throw new UnprocessableCommandException("CATEGORY_REQUIRED_INPUT_UNRESOLVED","Required workflow input is unresolved: "+input.getInputKey());
        if(input.getType().nullable())result.set(input.getInputKey(),JsonNodeFactory.instance.nullNode());
        continue;
      }
      try{CanonicalValueValidator.requireValid(input.getType(),value);}catch(IllegalArgumentException ex){throw new UnprocessableCommandException("CATEGORY_MAPPING_TYPE_MISMATCH","Mapping for "+input.getInputKey()+" produced an incompatible value");}
      result.set(input.getInputKey(),value.deepCopy());
    }
    return result;
  }

  /** Resolves the categoryKey of a pinned CategoryVersion for SYSTEM_CONTEXT mapping. */
  public String categoryKeyOf(UUID categoryVersionId){
    return categoryVersions.findById(categoryVersionId)
        .flatMap(v->categories.findById(v.getTicketCategoryId()))
        .map(com.fpt.workflow.ticketcategory.domain.TicketCategory::getKey)
        .orElseThrow(()->new UnprocessableCommandException("CATEGORY_VERSION_NOT_FOUND","Published CategoryVersion was not found"));
  }

  private JsonNode resolve(TicketCategoryMapping mapping,Map<UUID,FormField> fields,JsonNode formData,UUID actorId,String categoryKey){
    return switch(mapping.getSourceType()){
      case FORM_FIELD -> { FormField field=fields.get(mapping.getSourceFormFieldId()); yield field==null?null:formData.get(field.getFieldKey()); }
      case SYSTEM_CONTEXT -> resolvePath(text(mapping.getSourceExpressionJson()),formData,actorId,categoryKey);
      case CONSTANT -> mapping.getConstantJson();
      case EXPRESSION -> evaluate(mapping.getSourceExpressionJson(),formData,actorId,categoryKey);
      case DEFAULT -> mapping.getDefaultJson();
    };
  }
  private JsonNode evaluate(JsonNode expression,JsonNode formData,UUID actorId,String categoryKey){
    if(expression==null)return null;
    if(expression.isNumber()||expression.isBoolean()||expression.isNull())return expression;
    if(expression.isTextual())return resolvePath(expression.asText(),formData,actorId,categoryKey);
    String path=expression.path("path").asText(null); if(path!=null)return resolvePath(path,formData,actorId,categoryKey);
    String op=expression.path("op").asText(""); JsonNode args=expression.path("args");
    if(("MULTIPLY".equals(op)||"ADD".equals(op))&&args.isArray()&&args.size()>0){
      java.math.BigDecimal result="MULTIPLY".equals(op)?java.math.BigDecimal.ONE:java.math.BigDecimal.ZERO;
      for(JsonNode arg:args){JsonNode value=evaluate(arg,formData,actorId,categoryKey);if(value==null||!value.isNumber())return null;result="MULTIPLY".equals(op)?result.multiply(value.decimalValue()):result.add(value.decimalValue());}
      return JsonNodeFactory.instance.numberNode(result);
    }
    throw new UnprocessableCommandException("CATEGORY_EXPRESSION_INVALID","Only allowlisted reference, ADD, and MULTIPLY expressions are supported");
  }
  private JsonNode resolvePath(String path,JsonNode formData,UUID actorId,String categoryKey){
    if(path==null)return null;
    path=path.replace("${","").replace("}","");
    if(path.startsWith("form."))return formData.get(path.substring(5));
    if("actor.id".equals(path)||"actor.userId".equals(path))return JsonNodeFactory.instance.textNode(actorId.toString());
    if("category.key".equals(path))return JsonNodeFactory.instance.textNode(categoryKey);
    throw new UnprocessableCommandException("CATEGORY_SYSTEM_CONTEXT_PATH_FORBIDDEN","System context path is not allowlisted: "+path);
  }
  private String text(JsonNode node){if(node==null)return null;return node.isTextual()?node.asText():node.path("path").asText(null);}
  private boolean missing(JsonNode value){return value==null||value.isMissingNode()||value.isNull();}
  public static boolean isAllowlistedSystemPath(String path){return SYSTEM_PATHS.contains(path==null?null:path.replace("${","").replace("}",""));}
}
