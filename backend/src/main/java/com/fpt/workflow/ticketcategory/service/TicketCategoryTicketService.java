package com.fpt.workflow.ticketcategory.service;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.form.repository.*;
import com.fpt.workflow.form.service.FormSubmissionService;
import com.fpt.workflow.operations.job.WorkflowJobTransactions;
import com.fpt.workflow.operations.outbox.OutboxTransactions;
import com.fpt.workflow.runtime.context.*;
import com.fpt.workflow.runtime.trigger.EventTriggerService;
import com.fpt.workflow.security.*;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.ticketcategory.dto.TicketCategoryDtos;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryVersionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class TicketCategoryTicketService {
  private final TicketCategoryService categories;private final TicketCategoryVersionRepository versions;private final FormVersionRepository forms;private final FormSubmissionService submissions;private final FormSubmissionRepository submissionRepository;private final CategoryMappingService mapping;
  private final EventTriggerService triggers;private final EventWorkflowInputSnapshotRepository snapshots;private final WorkflowJobTransactions jobs;private final OutboxTransactions outbox;private final ActorContextProvider actors;private final UuidGenerator uuids;private final PlatformClock clock;private final JdbcTemplate jdbc;
  public TicketCategoryTicketService(TicketCategoryService categories,TicketCategoryVersionRepository versions,FormVersionRepository forms,FormSubmissionService submissions,FormSubmissionRepository submissionRepository,CategoryMappingService mapping,EventTriggerService triggers,EventWorkflowInputSnapshotRepository snapshots,WorkflowJobTransactions jobs,OutboxTransactions outbox,ActorContextProvider actors,UuidGenerator uuids,PlatformClock clock,JdbcTemplate jdbc){this.categories=categories;this.versions=versions;this.forms=forms;this.submissions=submissions;this.submissionRepository=submissionRepository;this.mapping=mapping;this.triggers=triggers;this.snapshots=snapshots;this.jobs=jobs;this.outbox=outbox;this.actors=actors;this.uuids=uuids;this.clock=clock;this.jdbc=jdbc;}
  @Transactional @PreAuthorize("isAuthenticated()")
  public TicketCategoryDtos.CreatedTicket create(String categoryKey,TicketCategoryDtos.CreateTicket request,String commandId){
    var contract=categories.resolvePublishedForCreate(categoryKey,request.tenantId());
    if(!contract.categoryChecksum().equals(request.categoryChecksum())||!contract.formVersionId().equals(request.formVersionId())||!contract.formChecksum().equals(request.formChecksum())||!contract.mappingChecksum().equals(request.mappingChecksum()))throw new CommandConflictException("CATEGORY_CREATE_CONTRACT_CHANGED","The Published Category/Form/Mapping contract changed; reload the form");
    var categoryVersion=versions.findById(contract.categoryVersionId()).orElseThrow();var formVersion=forms.findById(contract.formVersionId()).orElseThrow();ActorContext actor=actors.requireActor();Instant now=clock.now();
    var submission=submissions.validateAndPersist(formVersion,request.formData(),actor,now);
    var inputs=mapping.resolveInputs(categoryVersion.getId(),contract.workflowVersionId(),request.formData(),actor.actorId(),categoryKey);
    UUID ticketId=uuids.generate(),revisionId=uuids.generate();String initialState=categoryVersion.getCreationPolicyJson().path("initialStateKey").asText("SUBMITTED");
    jdbc.update("INSERT INTO tickets(id,request_type_id,ticket_category_version_id,creator_id,status,data_json,data_revision,current_revision_id,current_form_submission_id,current_business_state_key,current_state_updated_at,created_at,updated_at,submitted_at,lock_version) VALUES (?,NULL,?,?,'SUBMITTED',?::jsonb,1,?,?,?,?,?,?,?,0)",ticketId,categoryVersion.getId(),actor.actorId(),request.formData().toString(),revisionId,submission.getId(),initialState,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
    jdbc.update("INSERT INTO ticket_revisions(id,ticket_id,revision_no,data_snapshot_json,source_schema_version,schema_checksum,submitted_by,submitted_at,change_reason) VALUES (?,?,1,?::jsonb,?,?,?,?,NULL)",revisionId,ticketId,request.formData().toString(),formVersion.getId().toString(),formVersion.getChecksum(),actor.actorId(),Timestamp.from(now));
    submission.bindContext(ticketId);submissionRepository.saveAndFlush(submission);
    var event=triggers.createRoot(ticketId,contract.workflowVersionId(),revisionId,null,null,"CATEGORY_TICKET_CREATE",commandId,JsonNodeFactory.instance.objectNode(),actor.actorId(),now).event();
    // Create-time mapped inputs are the revision-1 current pointer. §7.5 history rows are only
    // appended by an explicit revision remap; the runtime reads revision 1 from this row.
    snapshots.saveAndFlush(EventWorkflowInputSnapshot.create(event.getId(),categoryVersion.getId(),contract.workflowVersionId(),submission.getId(),inputs,categoryVersion.getMappingChecksum(),now));
    jdbc.update("INSERT INTO ticket_state_history(id,ticket_id,event_id,state_key,entered_at,metadata_json) VALUES (?,?,?,?,?,'{}'::jsonb)",uuids.generate(),ticketId,event.getId(),initialState,Timestamp.from(now));
    UUID cycleId=uuids.generate(),correlationId=uuids.generate();
    jobs.enqueue("EVENT_START","EVENT",event.getId(),JsonNodeFactory.instance.objectNode().put("eventId",event.getId().toString()).put("cycleId",cycleId.toString()).put("correlationId",correlationId.toString()).put("commandId",commandId),5,now,"event-start:"+event.getId());
    outbox.enqueue("TICKET_SUBMITTED","TICKET",ticketId,JsonNodeFactory.instance.objectNode().put("ticketId",ticketId.toString()).put("eventId",event.getId().toString()).put("categoryVersionId",categoryVersion.getId().toString()).put("formSubmissionId",submission.getId().toString()).put("workflowVersionId",contract.workflowVersionId().toString()),5,"ticket-submitted:"+event.getId());
    return new TicketCategoryDtos.CreatedTicket(ticketId,event.getId(),categoryVersion.getId(),submission.getId(),contract.workflowVersionId(),inputs,initialState,now);
  }
}
