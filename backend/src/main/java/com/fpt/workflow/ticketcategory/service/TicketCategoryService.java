package com.fpt.workflow.ticketcategory.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.domain.FormVersion;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.repository.FormVersionRepository;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import com.fpt.workflow.ticketcategory.domain.*;
import com.fpt.workflow.ticketcategory.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class TicketCategoryService {
  private final TicketCategoryRepository categories;private final TicketCategoryVersionRepository versions;private final FormVersionRepository forms;private final WorkflowVersionRepository workflows;private final TicketCategoryMappingRepository mappings;private final ObjectMapper objectMapper;
  public TicketCategoryService(TicketCategoryRepository categories,TicketCategoryVersionRepository versions,FormVersionRepository forms,WorkflowVersionRepository workflows,TicketCategoryMappingRepository mappings,ObjectMapper objectMapper){this.categories=categories;this.versions=versions;this.forms=forms;this.workflows=workflows;this.mappings=mappings;this.objectMapper=objectMapper;}
  @Transactional(readOnly=true)
  public List<CatalogItem> listCreatable(){return categories.findAllByLifecycleOrderByNameAsc("ACTIVE").stream().filter(c->c.getCurrentPublishedVersionId()!=null).map(c->new CatalogItem(c.getKey(),c.getName(),c.getDescription(),c.getCategoryGroup(),c.getIcon())).toList();}
  @Transactional(readOnly=true)
  public CreateContract resolvePublishedForCreate(String categoryKey){
    TicketCategory category=categories.findByKey(categoryKey).filter(c->"ACTIVE".equals(c.getLifecycle())).orElseThrow(()->new ResourceNotFoundException("TICKET_CATEGORY_NOT_FOUND","Active business intent was not found"));
    if(category.getCurrentPublishedVersionId()==null)throw new CommandConflictException("CATEGORY_NOT_CREATABLE","Business intent has no Published CategoryVersion");
    TicketCategoryVersion version=versions.findById(category.getCurrentPublishedVersionId()).filter(v->"PUBLISHED".equals(v.getStatus())).orElseThrow(()->new CommandConflictException("CATEGORY_BINDING_INVALID","Current CategoryVersion is not Published"));
    FormVersion form=forms.findById(version.getFormVersionId()).filter(v->"PUBLISHED".equals(v.getStatus())).orElseThrow(()->new CommandConflictException("CATEGORY_FORM_NOT_PUBLISHED","Pinned FormVersion is not Published"));
    WorkflowVersion workflow=workflows.findById(version.getWorkflowVersionId()).filter(v->v.getStatus()==WorkflowVersionStatus.PUBLISHED).orElseThrow(()->new CommandConflictException("CATEGORY_WORKFLOW_NOT_PUBLISHED","Pinned WorkflowVersion is not Published"));
    if(version.getMappingChecksum()==null||version.getMappingChecksum().isBlank())throw new CommandConflictException("CATEGORY_MAPPING_NOT_PUBLISHED","Published CategoryVersion has no mapping checksum");
    try{return new CreateContract(category.getKey(),category.getName(),category.getDescription(),category.getIcon(),version.getId(),version.getChecksum(),form.getId(),form.getVersionNo(),form.getChecksum(),objectMapper.treeToValue(form.getCompiledSchemaJson()!=null?form.getCompiledSchemaJson():form.getSchemaJson(),FormSchema.class),workflow.getId(),workflow.getVersionNo(),workflow.getChecksum(),version.getMappingChecksum());}
    catch(JsonProcessingException ex){throw new IllegalStateException("Published FormVersion cannot be decoded",ex);}
  }
  public record CatalogItem(String categoryKey,String name,String description,String categoryGroup,String icon){}
  public record CreateContract(String categoryKey,String name,String description,String icon,UUID categoryVersionId,String categoryChecksum,UUID formVersionId,int formVersionNo,String formChecksum,FormSchema form,UUID workflowVersionId,int workflowVersionNo,String workflowChecksum,String mappingChecksum){}
}
