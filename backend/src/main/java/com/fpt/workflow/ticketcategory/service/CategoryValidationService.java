package com.fpt.workflow.ticketcategory.service;
import com.fpt.workflow.definition.domain.WorkflowInputDefinition;
import com.fpt.workflow.definition.repository.*;
import com.fpt.workflow.form.repository.*;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.ticketcategory.domain.*;
import com.fpt.workflow.ticketcategory.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class CategoryValidationService {
  private final FormVersionRepository forms;private final FormFieldRepository fields;private final WorkflowVersionRepository workflows;private final WorkflowInputDefinitionRepository inputs;private final WorkflowStateDefinitionRepository states;private final TicketCategoryMappingRepository mappings;
  public CategoryValidationService(FormVersionRepository forms,FormFieldRepository fields,WorkflowVersionRepository workflows,WorkflowInputDefinitionRepository inputs,WorkflowStateDefinitionRepository states,TicketCategoryMappingRepository mappings){this.forms=forms;this.fields=fields;this.workflows=workflows;this.inputs=inputs;this.states=states;this.mappings=mappings;}
  public List<CategoryIssue> validate(TicketCategoryVersion version){
    List<CategoryIssue> issues=new ArrayList<>();
    var form=forms.findById(version.getFormVersionId()).orElse(null);
    if(form==null||!"PUBLISHED".equals(form.getStatus()))issues.add(issue("CATEGORY-FORM-NOT-PUBLISHED","formVersionId","Pinned FormVersion must be Published",version));
    var workflow=workflows.findById(version.getWorkflowVersionId()).orElse(null);
    if(workflow==null||workflow.getStatus()!=com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus.PUBLISHED)issues.add(issue("CATEGORY-WORKFLOW-NOT-PUBLISHED","workflowVersionId","Pinned WorkflowVersion must be Published",version));
    String initialState=version.getCreationPolicyJson().path("initialStateKey").asText("SUBMITTED");
    if(states.findAllByWorkflowVersionIdOrderByDisplayOrderAsc(version.getWorkflowVersionId()).stream().noneMatch(s->s.getStateKey().equals(initialState)))issues.add(issue("CATEGORY-INITIAL-STATE-INVALID","creationPolicy.initialStateKey","Initial business state must be declared by the pinned WorkflowVersion",version));
    Map<UUID,com.fpt.workflow.form.domain.FormField> sourceFields=new HashMap<>();fields.findAllByFormVersionIdOrderByOrdinalAsc(version.getFormVersionId()).forEach(f->sourceFields.put(f.getId(),f));
    Map<UUID,WorkflowInputDefinition> targets=new LinkedHashMap<>();inputs.findAllByWorkflowVersionIdOrderByOrdinalAsc(version.getWorkflowVersionId()).forEach(i->targets.put(i.getId(),i));
    Set<UUID> seen=new HashSet<>();
    for(TicketCategoryMapping mapping:mappings.findAllByCategoryVersionIdOrderByOrdinalAsc(version.getId())){
      WorkflowInputDefinition target=targets.get(mapping.getTargetWorkflowInputId());
      if(target==null){issues.add(issue("CATEGORY-MAPPING-TARGET-MISSING","mappings.target","Mapping target does not belong to the pinned WorkflowVersion",version));continue;}
      if(!seen.add(target.getId()))issues.add(issue("CATEGORY-MAPPING-DUPLICATE-TARGET","mappings."+target.getInputKey(),"Duplicate target mapping",version));
      validateMapping(mapping,target,sourceFields,issues,version);
    }
    for(WorkflowInputDefinition input:targets.values())if(input.isRequired()&&!seen.contains(input.getId())&&input.getDefaultJson()==null)issues.add(issue("CATEGORY-REQUIRED-INPUT-UNRESOLVED","inputs."+input.getInputKey(),"Required Workflow input has no mapping or default",version));
    return List.copyOf(issues);
  }

  private void validateMapping(TicketCategoryMapping mapping, WorkflowInputDefinition target,
      Map<UUID,com.fpt.workflow.form.domain.FormField> sourceFields, List<CategoryIssue> issues,
      TicketCategoryVersion version) {
    String path="mappings."+target.getInputKey();
    if(!Set.of("ERROR","USE_DEFAULT","NULL").contains(mapping.getOnMissing()))
      issues.add(issue("CATEGORY-ON-MISSING-INVALID",path+".onMissing","onMissing must be ERROR, USE_DEFAULT, or NULL",version));
    if("NULL".equals(mapping.getOnMissing())&&!target.getType().nullable())
      issues.add(issue("CATEGORY-NULL-NOT-ALLOWED",path+".onMissing","NULL onMissing requires a nullable target input",version));
    if("USE_DEFAULT".equals(mapping.getOnMissing())
        && mapping.getDefaultJson()==null
        && target.getDefaultJson()==null
        && target.isRequired())
      issues.add(issue("CATEGORY-REQUIRED-INPUT-UNRESOLVED",path+".onMissing","USE_DEFAULT requires a mapping or Workflow input default for required targets",version));
    switch(mapping.getSourceType()) {
      case FORM_FIELD -> {
        var source=sourceFields.get(mapping.getSourceFormFieldId());
        if(source==null)issues.add(issue("CATEGORY-MAPPING-SOURCE-MISSING",path,"Source field does not belong to the pinned FormVersion",version));
        else if(mapping.getTransformJson()==null&&!compatible(source.getType(),target.getType()))issues.add(issue("CATEGORY-MAPPING-TYPE-MISMATCH",path,"Source and target types are incompatible",version));
      }
      case SYSTEM_CONTEXT -> {
        String expression=text(mapping.getSourceExpressionJson());
        if(!CategoryMappingService.isAllowlistedSystemPath(expression))issues.add(issue("CATEGORY-EXPRESSION-INVALID",path,"System context path is not allowlisted",version));
      }
      case EXPRESSION -> {
        if(!safeExpression(mapping.getSourceExpressionJson()))issues.add(issue("CATEGORY-EXPRESSION-INVALID",path,"Expression is not an allowlisted deterministic mapping expression",version));
      }
      case CONSTANT -> {
        if(mapping.getConstantJson()==null)issues.add(issue("CATEGORY-CONSTANT-MISSING",path,"CONSTANT mapping requires constantJson",version));
        else validateValue(mapping.getConstantJson(),target,"CATEGORY-MAPPING-TYPE-MISMATCH",issues,version);
      }
      case DEFAULT -> {
        if(mapping.getDefaultJson()==null)issues.add(issue("CATEGORY-DEFAULT-MISSING",path,"DEFAULT mapping requires defaultJson",version));
      }
    }
    if(mapping.getTransformJson()!=null&&!CategoryMappingService.isSupportedTransform(mapping.getTransformJson()))
      issues.add(issue("CATEGORY-TRANSFORM-INVALID",path+".transform","Transform must use the allowlisted deterministic operations: TRIM, UPPER, LOWER, TO_STRING, TO_NUMBER, TO_INTEGER, or TO_BOOLEAN",version));
    else if(mapping.getTransformJson()!=null)validateTransformTypes(mapping,target,sourceFields,issues,version);
    if(mapping.getDefaultJson()!=null)validateValue(mapping.getDefaultJson(),target,"CATEGORY-DEFAULT-TYPE-MISMATCH",issues,version);
  }

  private void validateTransformTypes(TicketCategoryMapping mapping,WorkflowInputDefinition target,
      Map<UUID,com.fpt.workflow.form.domain.FormField> sourceFields,List<CategoryIssue> issues,
      TicketCategoryVersion version){
    var source=mapping.getSourceType()==MappingSourceType.FORM_FIELD?sourceFields.get(mapping.getSourceFormFieldId()):null;
    com.fpt.workflow.shared.domain.value.TypeDescriptor current=source==null?null:source.getType();
    String path="mappings."+target.getInputKey()+".transform";
    for(String operation:CategoryMappingService.transformOperations(mapping.getTransformJson())){
      if(current==null)continue;
      var type=current.type();
      switch(operation){
        case "TRIM","UPPER","UPPERCASE","LOWER","LOWERCASE" -> {
          if(type!=com.fpt.workflow.shared.domain.value.CanonicalValueType.STRING){issues.add(issue("CATEGORY-TRANSFORM-TYPE-MISMATCH",path,"String transform requires a STRING source value",version));return;}
          current=com.fpt.workflow.shared.domain.value.TypeDescriptor.required(com.fpt.workflow.shared.domain.value.CanonicalValueType.STRING).withNullable(current.nullable());
        }
        case "TO_STRING" -> current=new com.fpt.workflow.shared.domain.value.TypeDescriptor(com.fpt.workflow.shared.domain.value.CanonicalValueType.STRING,current.nullable(),null);
        case "TO_NUMBER" -> {
          if(type!=com.fpt.workflow.shared.domain.value.CanonicalValueType.STRING&&type!=com.fpt.workflow.shared.domain.value.CanonicalValueType.NUMBER&&type!=com.fpt.workflow.shared.domain.value.CanonicalValueType.INTEGER){issues.add(issue("CATEGORY-TRANSFORM-TYPE-MISMATCH",path,"TO_NUMBER requires STRING, NUMBER, or INTEGER",version));return;}
          current=new com.fpt.workflow.shared.domain.value.TypeDescriptor(com.fpt.workflow.shared.domain.value.CanonicalValueType.NUMBER,current.nullable(),null);
        }
        case "TO_INTEGER" -> {
          if(type!=com.fpt.workflow.shared.domain.value.CanonicalValueType.STRING&&type!=com.fpt.workflow.shared.domain.value.CanonicalValueType.NUMBER&&type!=com.fpt.workflow.shared.domain.value.CanonicalValueType.INTEGER){issues.add(issue("CATEGORY-TRANSFORM-TYPE-MISMATCH",path,"TO_INTEGER requires STRING, NUMBER, or INTEGER",version));return;}
          current=new com.fpt.workflow.shared.domain.value.TypeDescriptor(com.fpt.workflow.shared.domain.value.CanonicalValueType.INTEGER,current.nullable(),null);
        }
        case "TO_BOOLEAN" -> {
          if(type!=com.fpt.workflow.shared.domain.value.CanonicalValueType.STRING&&type!=com.fpt.workflow.shared.domain.value.CanonicalValueType.BOOLEAN){issues.add(issue("CATEGORY-TRANSFORM-TYPE-MISMATCH",path,"TO_BOOLEAN requires STRING or BOOLEAN",version));return;}
          current=new com.fpt.workflow.shared.domain.value.TypeDescriptor(com.fpt.workflow.shared.domain.value.CanonicalValueType.BOOLEAN,current.nullable(),null);
        }
        default -> { return; }
      }
    }
    if(current!=null&&!compatible(current,target.getType()))issues.add(issue("CATEGORY-MAPPING-TYPE-MISMATCH",path,"Transformed value is incompatible with target type",version));
  }

  /**
   * A tenant override changes only the workflow. Its published input contract must therefore
   * remain compatible with the category's explicit mapping and shared FormVersion.
   */
  public List<CategoryIssue> validateWorkflowCompatibility(TicketCategoryVersion baseVersion, UUID workflowVersionId) {
    List<CategoryIssue> issues=new ArrayList<>();
    var workflow=workflows.findById(workflowVersionId).orElse(null);
    if(workflow==null||workflow.getStatus()!=com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus.PUBLISHED){
      issues.add(issue("CATEGORY-WORKFLOW-NOT-PUBLISHED","workflowVersionId","Workflow override must point to a Published WorkflowVersion",baseVersion));
      return List.copyOf(issues);
    }
    Map<String,WorkflowInputDefinition> baseInputs=new LinkedHashMap<>();
    inputs.findAllByWorkflowVersionIdOrderByOrdinalAsc(baseVersion.getWorkflowVersionId()).forEach(i->baseInputs.put(i.getInputKey(),i));
    Map<String,WorkflowInputDefinition> overrideInputs=new LinkedHashMap<>();
    inputs.findAllByWorkflowVersionIdOrderByOrdinalAsc(workflowVersionId).forEach(i->overrideInputs.put(i.getInputKey(),i));
    for(WorkflowInputDefinition base:baseInputs.values()){
      WorkflowInputDefinition override=overrideInputs.get(base.getInputKey());
      if(override==null||!compatible(base.getType(),override.getType())){
        issues.add(issue("CATEGORY-WORKFLOW-CONTRACT_MISMATCH","workflowVersionId","Workflow override must keep the same input keys and data types",baseVersion));
      }
    }
    for(WorkflowInputDefinition override:overrideInputs.values()){
      if(!baseInputs.containsKey(override.getInputKey())){
        issues.add(issue("CATEGORY-WORKFLOW-CONTRACT_MISMATCH","workflowVersionId","Workflow override must not add a new input outside the category contract",baseVersion));
      }
    }
    String initialState=baseVersion.getCreationPolicyJson().path("initialStateKey").asText("SUBMITTED");
    if(states.findAllByWorkflowVersionIdOrderByDisplayOrderAsc(workflowVersionId).stream().noneMatch(s->s.getStateKey().equals(initialState))){
      issues.add(issue("CATEGORY-INITIAL-STATE-INVALID","creationPolicy.initialStateKey","Workflow override must declare the category initial business state",baseVersion));
    }
    return List.copyOf(issues);
  }
  private void validateValue(com.fasterxml.jackson.databind.JsonNode value,WorkflowInputDefinition input,String code,List<CategoryIssue> issues,TicketCategoryVersion version){try{CanonicalValueValidator.requireValid(input.getType(),value);}catch(IllegalArgumentException ex){issues.add(issue(code,"inputs."+input.getInputKey(),"Configured value is incompatible with target type",version));}}
  private boolean compatible(com.fpt.workflow.shared.domain.value.TypeDescriptor a,com.fpt.workflow.shared.domain.value.TypeDescriptor b){
    if(a.nullable()&&!b.nullable())return false;
    if(a.type()==com.fpt.workflow.shared.domain.value.CanonicalValueType.ARRAY||b.type()==com.fpt.workflow.shared.domain.value.CanonicalValueType.ARRAY)
      return a.type()==b.type()&&a.itemType()!=null&&b.itemType()!=null&&compatible(a.itemType(),b.itemType());
    return a.type()==b.type()||a.type()==com.fpt.workflow.shared.domain.value.CanonicalValueType.INTEGER&&b.type()==com.fpt.workflow.shared.domain.value.CanonicalValueType.NUMBER;
  }
  private boolean safeExpression(com.fasterxml.jackson.databind.JsonNode expression){
    if(expression==null||expression.isNull())return false;
    if(expression.isNumber()||expression.isBoolean())return true;
    if(expression.isTextual())return expression.asText().startsWith("form.")||CategoryMappingService.isAllowlistedSystemPath(expression.asText());
    if(!expression.isObject())return false;
    if(expression.has("path"))return expression.size()==1&&safeExpression(expression.get("path"));
    String op=expression.path("op").asText(null);com.fasterxml.jackson.databind.JsonNode args=expression.get("args");
    if(!("ADD".equals(op)||"MULTIPLY".equals(op))||args==null||!args.isArray()||args.isEmpty())return false;
    for(com.fasterxml.jackson.databind.JsonNode arg:args)if(!safeExpression(arg))return false;
    return true;
  }
  private String text(com.fasterxml.jackson.databind.JsonNode n){return n==null?null:n.isTextual()?n.asText():n.path("path").asText(null);}
  private CategoryIssue issue(String code,String path,String message,TicketCategoryVersion version){return new CategoryIssue(code,"ERROR","TICKET_CATEGORY_VERSION",version.getId().toString(),path,message);}
}
