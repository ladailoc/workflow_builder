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
    var form=forms.findById(version.getFormVersionId()).orElse(null);if(form==null||!"PUBLISHED".equals(form.getStatus()))issues.add(issue("CATEGORY-FORM-NOT-PUBLISHED","formVersionId","Pinned FormVersion must be Published",version));
    var workflow=workflows.findById(version.getWorkflowVersionId()).orElse(null);if(workflow==null||workflow.getStatus()!=com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus.PUBLISHED)issues.add(issue("CATEGORY-WORKFLOW-NOT-PUBLISHED","workflowVersionId","Pinned WorkflowVersion must be Published",version));
    String initialState=version.getCreationPolicyJson().path("initialStateKey").asText("SUBMITTED");
    if(states.findAllByWorkflowVersionIdOrderByDisplayOrderAsc(version.getWorkflowVersionId()).stream().noneMatch(s->s.getStateKey().equals(initialState)))issues.add(issue("CATEGORY-INITIAL-STATE-INVALID","creationPolicy.initialStateKey","Initial business state must be declared by the pinned WorkflowVersion",version));
    Map<UUID,com.fpt.workflow.form.domain.FormField> sourceFields=new HashMap<>();fields.findAllByFormVersionIdOrderByOrdinalAsc(version.getFormVersionId()).forEach(f->sourceFields.put(f.getId(),f));
    Map<UUID,WorkflowInputDefinition> targets=new LinkedHashMap<>();inputs.findAllByWorkflowVersionIdOrderByOrdinalAsc(version.getWorkflowVersionId()).forEach(i->targets.put(i.getId(),i));
    Set<UUID> seen=new HashSet<>();
    for(TicketCategoryMapping mapping:mappings.findAllByCategoryVersionIdOrderByOrdinalAsc(version.getId())){
      WorkflowInputDefinition target=targets.get(mapping.getTargetWorkflowInputId());if(target==null){issues.add(issue("CATEGORY-MAPPING-TARGET-MISSING","mappings.target","Mapping target does not belong to the pinned WorkflowVersion",version));continue;}
      if(!seen.add(target.getId()))issues.add(issue("CATEGORY-MAPPING-DUPLICATE-TARGET","mappings."+target.getInputKey(),"Duplicate target mapping",version));
      if(mapping.getSourceType()==MappingSourceType.FORM_FIELD){var source=sourceFields.get(mapping.getSourceFormFieldId());if(source==null)issues.add(issue("CATEGORY-MAPPING-SOURCE-MISSING","mappings."+target.getInputKey(),"Source field does not belong to the pinned FormVersion",version));else if(!compatible(source.getType(),target.getType()))issues.add(issue("CATEGORY-MAPPING-TYPE-MISMATCH","mappings."+target.getInputKey(),"Source and target types are incompatible",version));}
      if(mapping.getSourceType()==MappingSourceType.SYSTEM_CONTEXT&&!CategoryMappingService.isAllowlistedSystemPath(text(mapping.getSourceExpressionJson())))issues.add(issue("CATEGORY-EXPRESSION-INVALID","mappings."+target.getInputKey(),"System context path is not allowlisted",version));
      if(mapping.getSourceType()==MappingSourceType.CONSTANT&&mapping.getConstantJson()!=null)validateValue(mapping.getConstantJson(),target,"CATEGORY-MAPPING-TYPE-MISMATCH",issues,version);
      if(mapping.getDefaultJson()!=null)validateValue(mapping.getDefaultJson(),target,"CATEGORY-DEFAULT-TYPE-MISMATCH",issues,version);
    }
    for(WorkflowInputDefinition input:targets.values())if(input.isRequired()&&!seen.contains(input.getId())&&input.getDefaultJson()==null)issues.add(issue("CATEGORY-REQUIRED-INPUT-UNRESOLVED","inputs."+input.getInputKey(),"Required Workflow input has no mapping or default",version));
    return List.copyOf(issues);
  }
  private void validateValue(com.fasterxml.jackson.databind.JsonNode value,WorkflowInputDefinition input,String code,List<CategoryIssue> issues,TicketCategoryVersion version){try{CanonicalValueValidator.requireValid(input.getType(),value);}catch(IllegalArgumentException ex){issues.add(issue(code,"inputs."+input.getInputKey(),"Configured value is incompatible with target type",version));}}
  private boolean compatible(com.fpt.workflow.shared.domain.value.TypeDescriptor a,com.fpt.workflow.shared.domain.value.TypeDescriptor b){return a.type()==b.type()||a.type()==com.fpt.workflow.shared.domain.value.CanonicalValueType.INTEGER&&b.type()==com.fpt.workflow.shared.domain.value.CanonicalValueType.NUMBER;}
  private String text(com.fasterxml.jackson.databind.JsonNode n){return n==null?null:n.isTextual()?n.asText():n.path("path").asText(null);}
  private CategoryIssue issue(String code,String path,String message,TicketCategoryVersion version){return new CategoryIssue(code,"ERROR","TICKET_CATEGORY_VERSION",version.getId().toString(),path,message);}
}
