package com.fpt.workflow.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.form.domain.FormDefinition;
import com.fpt.workflow.form.domain.FormField;
import com.fpt.workflow.form.domain.FormVersion;
import com.fpt.workflow.form.engine.DynamicFormEngine;
import com.fpt.workflow.form.engine.FormIssueSeverity;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.engine.FormValidationPhase;
import com.fpt.workflow.form.engine.FormValidationResult;
import com.fpt.workflow.form.repository.FormDefinitionRepository;
import com.fpt.workflow.form.repository.FormFieldRepository;
import com.fpt.workflow.form.repository.FormVersionRepository;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FormManagementService {
  private final FormDefinitionRepository forms; private final FormVersionRepository versions;
  private final FormFieldRepository fields; private final DynamicFormEngine engine;
  private final ObjectMapper mapper; private final UuidGenerator uuids;
  private final ActorContextProvider actors; private final PlatformClock clock;
  public FormManagementService(FormDefinitionRepository forms, FormVersionRepository versions,
      FormFieldRepository fields, DynamicFormEngine engine, ObjectMapper mapper, UuidGenerator uuids,
      ActorContextProvider actors, PlatformClock clock) {
    this.forms=forms;this.versions=versions;this.fields=fields;this.engine=engine;this.mapper=mapper;
    this.uuids=uuids;this.actors=actors;this.clock=clock;
  }

  @Transactional
  public FormView create(CreateForm command) {
    UUID actor=actors.requireActor().actorId(); Instant now=clock.now(); UUID id=uuids.generate();
    FormDefinition form=forms.save(FormDefinition.create(id,command.key(),command.name(),command.description(),actor,now));
    FormVersion draft=versions.save(FormVersion.draft(uuids.generate(),id,1,actor,now,command.schema()));
    syncFields(draft); form.pointToDraft(draft.getId(),now); return new FormView(form,draft);
  }

  @Transactional
  public FormVersion createDraft(UUID formId) {
    FormDefinition form=requireForm(formId); UUID actor=actors.requireActor().actorId(); Instant now=clock.now();
    if(form.getActiveDraftVersionId()!=null) return requireVersion(formId,form.getActiveDraftVersionId());
    JsonNode schema=form.getCurrentPublishedVersionId()==null?mapper.createObjectNode().put("formKey",form.getKey()).put("formType","TICKET_FORM").set("fields",mapper.createArrayNode()):requireVersion(formId,form.getCurrentPublishedVersionId()).getSchemaJson();
    FormVersion draft=versions.save(FormVersion.draft(uuids.generate(),formId,(int)versions.countByFormId(formId)+1,actor,now,schema));
    syncFields(draft);form.pointToDraft(draft.getId(),now);return draft;
  }

  @Transactional
  public FormVersion update(UUID formId,UUID versionId,long expectedRevision,JsonNode schema){
    FormVersion version=requireVersion(formId,versionId);version.update(expectedRevision,schema);syncFields(version);return version;
  }
  @Transactional(readOnly=true)
  public FormValidationResult validate(UUID formId,UUID versionId){return validate(requireVersion(formId,versionId),FormValidationPhase.PUBLISH);}
  @Transactional
  public FormVersion publish(UUID formId,UUID versionId,long expectedRevision){
    FormDefinition form=requireForm(formId);FormVersion version=requireVersion(formId,versionId);
    if(version.getRevision()!=expectedRevision)throw new IllegalStateException("STALE_FORM_REVISION");
    FormValidationResult result=validate(version,FormValidationPhase.PUBLISH);if(!result.valid())throw new IllegalStateException("FORM_PUBLISH_VALIDATION_FAILED: "+result.issues().stream().filter(i->i.severity()==FormIssueSeverity.ERROR).map(i->i.code()).toList());
    if(form.getCurrentPublishedVersionId()!=null){FormVersion old=requireVersion(formId,form.getCurrentPublishedVersionId());if(!old.getId().equals(versionId))old.supersede();}
    JsonNode compiled=version.getSchemaJson();version.publish(checksum(compiled),compiled,actors.requireActor().actorId(),clock.now());form.pointToPublished(versionId,clock.now());return version;
  }
  @Transactional(readOnly=true) public List<FormVersion> versions(UUID formId){requireForm(formId);return versions.findAllByFormIdOrderByVersionNoDesc(formId);}
  @Transactional(readOnly=true)
  public List<FormField> fields(UUID formId, UUID versionId) {
    requireVersion(formId, versionId);
    return fields.findAllByFormVersionIdOrderByOrdinalAsc(versionId);
  }
  @Transactional(readOnly=true)
  public List<FormCatalogItem> publishedCatalog(){
    return forms.findAllByLifecycleOrderByNameAsc("ACTIVE").stream().filter(form->form.getCurrentPublishedVersionId()!=null).map(form->versions.findById(form.getCurrentPublishedVersionId()).filter(version->"PUBLISHED".equals(version.getStatus())).map(version->new FormCatalogItem(form.getId(),form.getKey(),form.getName(),form.getDescription(),version.getId(),version.getVersionNo(),version.getChecksum())).orElse(null)).filter(item->item!=null).toList();
  }
  private FormValidationResult validate(FormVersion version,FormValidationPhase phase){try{return engine.validateSchema(mapper.treeToValue(version.getSchemaJson(),FormSchema.class),phase,contextSchema());}catch(JsonProcessingException|IllegalArgumentException ex){return new FormValidationResult(List.of(new com.fpt.workflow.form.engine.FormValidationIssue("FORM.SCHEMA_INVALID",FormIssueSeverity.ERROR,"$",ex.getMessage())));}}
  private void syncFields(FormVersion version){
    try {
      FormSchema schema = mapper.treeToValue(version.getSchemaJson(), FormSchema.class);
      Map<String, UUID> existingIds = new HashMap<>();
      fields.findAllByFormVersionIdOrderByOrdinalAsc(version.getId()).forEach(field -> existingIds.put(field.getFieldKey(), field.getId()));
      fields.deleteAllByFormVersionId(version.getId());
      fields.flush();
      fields.saveAll(schema.fields().stream().map(field -> FormField.create(
          existingIds.getOrDefault(field.key(), uuids.generate()), version.getId(), field.key(),
          field.label(), field.order(), field.type(), field.sensitive(), null)).toList());
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("FORM.SCHEMA_INVALID", ex);
    }
  }
  private ExpressionSchema contextSchema(){return new ExpressionSchema(Map.of("actor.id",TypeDescriptor.required(CanonicalValueType.USER_ID),"category.key",TypeDescriptor.required(CanonicalValueType.STRING)),Set.of());}
  private FormDefinition requireForm(UUID id){return forms.findById(id).orElseThrow(()->new IllegalArgumentException("Form not found: "+id));}
  private FormVersion requireVersion(UUID formId,UUID id){FormVersion v=versions.findById(id).orElseThrow(()->new IllegalArgumentException("FormVersion not found: "+id));if(!v.getFormId().equals(formId))throw new IllegalArgumentException("FormVersion does not belong to Form");return v;}
  private String checksum(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
  public record CreateForm(String key,String name,String description,JsonNode schema){}
  public record FormView(FormDefinition form,FormVersion draft){}
  public record FormCatalogItem(UUID formId,String formKey,String name,String description,UUID formVersionId,int versionNo,String checksum){}
}
