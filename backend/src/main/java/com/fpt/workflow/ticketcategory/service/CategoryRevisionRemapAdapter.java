package com.fpt.workflow.ticketcategory.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.form.domain.FormVersion;
import com.fpt.workflow.form.repository.FormVersionRepository;
import com.fpt.workflow.form.service.FormSubmissionService;
import com.fpt.workflow.rework.service.RevisionInputRemapPort;
import com.fpt.workflow.runtime.context.EventWorkflowInputRevision;
import com.fpt.workflow.runtime.context.EventWorkflowInputRevisionRepository;
import com.fpt.workflow.runtime.context.EventWorkflowInputSnapshotRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryVersionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends a new immutable WorkflowInputRevision for a submitted REQUEST_REVISION by rerunning the
 * SAME pinned CategoryVersion mapping contract against the new business data. The Event's exact
 * WorkflowVersion, the original FormSubmission, and every prior input revision are preserved.
 */
@Component
public class CategoryRevisionRemapAdapter implements RevisionInputRemapPort {
  private final JdbcTemplate jdbc;
  private final TicketCategoryVersionRepository categoryVersions;
  private final EventWorkflowInputSnapshotRepository snapshots;
  private final EventWorkflowInputRevisionRepository inputRevisions;
  private final FormVersionRepository forms;
  private final FormSubmissionService submissions;
  private final CategoryMappingService mapping;
  private final UuidGenerator uuids;

  public CategoryRevisionRemapAdapter(JdbcTemplate jdbc,TicketCategoryVersionRepository categoryVersions,
      EventWorkflowInputSnapshotRepository snapshots,EventWorkflowInputRevisionRepository inputRevisions,
      FormVersionRepository forms,FormSubmissionService submissions,CategoryMappingService mapping,
      UuidGenerator uuids){
    this.jdbc=jdbc;this.categoryVersions=categoryVersions;this.snapshots=snapshots;
    this.inputRevisions=inputRevisions;this.forms=forms;this.submissions=submissions;
    this.mapping=mapping;this.uuids=uuids;
  }

  @Override @Transactional(propagation=Propagation.MANDATORY)
  public Optional<RemappedInputs> remap(UUID eventId,UUID workflowVersionId,UUID ticketId,
      UUID newTicketRevisionId,JsonNode businessData,UUID actorId,Instant now){
    UUID categoryVersionId=categoryVersionIdOf(eventId);
    if(categoryVersionId==null)return Optional.empty();
    var categoryVersion=categoryVersions.findById(categoryVersionId)
        .orElseThrow(()->new UnprocessableCommandException("CATEGORY_VERSION_NOT_FOUND","Event CategoryVersion was not found"));
    FormVersion formVersion=forms.findById(categoryVersion.getFormVersionId())
        .filter(v->"PUBLISHED".equals(v.getStatus()) || "SUPERSEDED".equals(v.getStatus()))
        .orElseThrow(()->new UnprocessableCommandException("CATEGORY_FORM_NOT_PUBLISHED","Pinned FormVersion is not Published"));
    var prior=snapshots.findById(eventId)
        .orElseThrow(()->new UnprocessableCommandException("EVENT_INPUT_SNAPSHOT_MISSING","Event has no Category input snapshot"));
    // CategoryVersion stores the default workflow. The immutable Event snapshot records the
    // effective workflow, so tenant override events can be remapped against their own contract.
    if(prior.getWorkflowVersionId()!=null && !prior.getWorkflowVersionId().equals(workflowVersionId))
      throw new UnprocessableCommandException("CATEGORY_WORKFLOW_MISMATCH","Event is not bound to the requested WorkflowVersion");
    var submission=submissions.validateAndPersistRevision(formVersion,businessData,new ActorContext(actorId,actorId.toString(),Set.of(),Set.of()),ticketId,now);
    ObjectNode inputs=mapping.resolveInputs(categoryVersionId,workflowVersionId,formVersion.getId(),
        businessData,actorId,mapping.categoryKeyOf(categoryVersionId));
    long nextRevision=inputRevisions.maxInputRevision(eventId)+1;
    inputRevisions.saveAndFlush(EventWorkflowInputRevision.append(uuids.generate(),eventId,categoryVersionId,
        submission.getId(),newTicketRevisionId,nextRevision,inputs,prior.getMappingChecksum(),now));
    return Optional.of(new RemappedInputs(submission.getId(),nextRevision));
  }

  /** Resolves the CategoryVersion pinned by the Event's create-time snapshot; legacy events have none. */
  private UUID categoryVersionIdOf(UUID eventId){
    return jdbc.query("SELECT category_version_id FROM event_workflow_input_snapshots WHERE event_id=?",
        rs->rs.next()?rs.getObject("category_version_id",UUID.class):null,eventId);
  }
}
