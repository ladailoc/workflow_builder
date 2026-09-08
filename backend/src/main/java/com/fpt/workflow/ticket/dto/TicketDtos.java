package com.fpt.workflow.ticket.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.domain.TicketRevision;
import com.fpt.workflow.ticket.domain.TicketSubject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TicketDtos {

  private TicketDtos() {}

  public record SubjectInput(
      String subjectType, UUID subjectRefId, String roleKey, String sourceField) {}

  public record CreateDraft(UUID requestTypeId, JsonNode dataJson, List<SubjectInput> subjects) {}

  public record UpdateDraft(
      JsonNode dataJson, List<SubjectInput> subjects, long expectedDataRevision) {
    public UpdateDraft(JsonNode dataJson, List<SubjectInput> subjects) {
      this(dataJson, subjects, 0);
    }
  }

  public record Submit(
      UUID sourceWorkflowVersionId,
      String schemaChecksum,
      String changeReason,
      long expectedDataRevision) {
    public Submit(UUID sourceWorkflowVersionId, String schemaChecksum, String changeReason) {
      this(sourceWorkflowVersionId, schemaChecksum, changeReason, 0);
    }
  }

  public record RecordRevision(
      JsonNode dataJson,
      UUID sourceWorkflowVersionId,
      String schemaChecksum,
      String changeReason,
      List<SubjectInput> subjects) {}

  public record TicketView(
      UUID id,
      UUID requestTypeId,
      UUID creatorId,
      TicketStatus status,
      JsonNode dataJson,
      long dataRevision,
      UUID currentRevisionId,
      Instant createdAt,
      Instant updatedAt,
      Instant submittedAt,
      Instant completedAt,
      long lockVersion) {

    public static TicketView from(Ticket ticket) {
      return new TicketView(
          ticket.getId(),
          ticket.getRequestTypeId(),
          ticket.getCreatorId(),
          ticket.getStatus(),
          ticket.getDataJson(),
          ticket.getDataRevision(),
          ticket.getCurrentRevisionId(),
          ticket.getCreatedAt(),
          ticket.getUpdatedAt(),
          ticket.getSubmittedAt(),
          ticket.getCompletedAt(),
          ticket.getLockVersion());
    }
  }

  public record RevisionView(
      UUID id,
      UUID ticketId,
      long revisionNo,
      JsonNode dataSnapshotJson,
      String sourceSchemaVersion,
      String schemaChecksum,
      UUID submittedBy,
      Instant submittedAt,
      String changeReason) {

    public static RevisionView from(TicketRevision revision) {
      return new RevisionView(
          revision.getId(),
          revision.getTicketId(),
          revision.getRevisionNo(),
          revision.getDataSnapshotJson(),
          revision.getSourceSchemaVersion(),
          revision.getSchemaChecksum(),
          revision.getSubmittedBy(),
          revision.getSubmittedAt(),
          revision.getChangeReason());
    }
  }

  public record SubjectView(
      UUID id,
      UUID ticketId,
      String subjectType,
      UUID subjectRefId,
      String roleKey,
      String sourceField,
      Instant createdAt) {

    public static SubjectView from(TicketSubject subject) {
      return new SubjectView(
          subject.getId(),
          subject.getTicketId(),
          subject.getSubjectType(),
          subject.getSubjectRefId(),
          subject.getRoleKey(),
          subject.getSourceField(),
          subject.getCreatedAt());
    }
  }

  public record AggregateView(
      TicketView ticket, List<RevisionView> revisions, List<SubjectView> subjects) {

    public AggregateView {
      revisions = List.copyOf(revisions);
      subjects = List.copyOf(subjects);
    }
  }
}
