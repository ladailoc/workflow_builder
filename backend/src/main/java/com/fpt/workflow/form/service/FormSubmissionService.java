package com.fpt.workflow.form.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.form.domain.*;
import com.fpt.workflow.form.engine.*;
import com.fpt.workflow.form.repository.FormSubmissionRepository;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class FormSubmissionService {
  private final DynamicFormEngine engine;private final FormSubmissionRepository submissions;private final ObjectMapper mapper;private final UuidGenerator uuids;
  public FormSubmissionService(DynamicFormEngine engine,FormSubmissionRepository submissions,ObjectMapper mapper,UuidGenerator uuids){this.engine=engine;this.submissions=submissions;this.mapper=mapper;this.uuids=uuids;}
  public FormSubmission validateAndPersist(FormVersion version,JsonNode data,ActorContext actor,Instant now){
    try{
      FormSchema schema=mapper.treeToValue(version.getCompiledSchemaJson()!=null?version.getCompiledSchemaJson():version.getSchemaJson(),FormSchema.class);
      var context=JsonNodeFactory.instance.objectNode();context.putObject("actor").put("id",actor.actorId().toString());context.putObject("organization");context.putObject("category");
      List<FormValidationIssue> errors=engine.validateSubmission(schema,data,context,new ExpressionSchema(Map.of("actor.id",com.fpt.workflow.shared.domain.value.TypeDescriptor.required(com.fpt.workflow.shared.domain.value.CanonicalValueType.USER_ID),"category.key",com.fpt.workflow.shared.domain.value.TypeDescriptor.required(com.fpt.workflow.shared.domain.value.CanonicalValueType.STRING)),Set.of())).issues().stream().filter(i->i.severity()==FormIssueSeverity.ERROR).toList();
      if(!errors.isEmpty())throw new UnprocessableCommandException("FORM_SUBMISSION_INVALID",errors.stream().limit(5).map(i->i.code()+"@"+i.fieldPath()).reduce((a,b)->a+", "+b).orElse("Invalid form"));
      return submissions.saveAndFlush(FormSubmission.ticketCreate(uuids.generate(),version.getId(),data,version.getChecksum(),actor.actorId(),now));
    }catch(JsonProcessingException ex){throw new IllegalStateException("Published FormVersion cannot be decoded",ex);}
  }

  /** New REVISION_REQUEST submission bound to the Ticket; the create submission is never replaced. */
  public FormSubmission validateAndPersistRevision(FormVersion version,JsonNode data,ActorContext actor,UUID ticketId,Instant now){
    try{
      FormSchema schema=mapper.treeToValue(version.getCompiledSchemaJson()!=null?version.getCompiledSchemaJson():version.getSchemaJson(),FormSchema.class);
      var context=JsonNodeFactory.instance.objectNode();context.putObject("actor").put("id",actor.actorId().toString());context.putObject("organization");context.putObject("category");
      List<FormValidationIssue> errors=engine.validateSubmission(schema,data,context,new ExpressionSchema(Map.of("actor.id",com.fpt.workflow.shared.domain.value.TypeDescriptor.required(com.fpt.workflow.shared.domain.value.CanonicalValueType.USER_ID),"category.key",com.fpt.workflow.shared.domain.value.TypeDescriptor.required(com.fpt.workflow.shared.domain.value.CanonicalValueType.STRING)),Set.of())).issues().stream().filter(i->i.severity()==FormIssueSeverity.ERROR).toList();
      if(!errors.isEmpty())throw new UnprocessableCommandException("FORM_SUBMISSION_INVALID",errors.stream().limit(5).map(i->i.code()+"@"+i.fieldPath()).reduce((a,b)->a+", "+b).orElse("Invalid form"));
      return submissions.saveAndFlush(FormSubmission.revision(uuids.generate(),version.getId(),data,version.getChecksum(),ticketId,actor.actorId(),now));
    }catch(JsonProcessingException ex){throw new IllegalStateException("Published FormVersion cannot be decoded",ex);}
  }
}
