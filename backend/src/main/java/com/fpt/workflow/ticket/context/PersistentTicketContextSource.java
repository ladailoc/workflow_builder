package com.fpt.workflow.ticket.context;

import com.fpt.workflow.runtime.context.TicketContextSnapshot;
import com.fpt.workflow.runtime.context.TicketContextSource;
import com.fpt.workflow.runtime.context.TicketSubjectSnapshot;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.domain.TicketRevision;
import com.fpt.workflow.ticket.repository.TicketRepository;
import com.fpt.workflow.ticket.repository.TicketRevisionRepository;
import com.fpt.workflow.ticket.repository.TicketSubjectRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PersistentTicketContextSource implements TicketContextSource {

  private final TicketRepository ticketRepository;
  private final TicketRevisionRepository revisionRepository;
  private final TicketSubjectRepository subjectRepository;

  public PersistentTicketContextSource(
      TicketRepository ticketRepository,
      TicketRevisionRepository revisionRepository,
      TicketSubjectRepository subjectRepository) {
    this.ticketRepository = ticketRepository;
    this.revisionRepository = revisionRepository;
    this.subjectRepository = subjectRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public TicketContextSnapshot load(UUID ticketId, UUID revisionId) {
    Ticket ticket =
        ticketRepository
            .findById(ticketId)
            .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));
    TicketRevision revision =
        revisionRepository
            .findById(revisionId)
            .orElseThrow(
                () -> new IllegalArgumentException("Ticket revision not found: " + revisionId));
    if (!revision.getTicketId().equals(ticket.getId())) {
      throw new IllegalStateException("Event revision does not belong to its Ticket");
    }
    return new TicketContextSnapshot(
        ticket.getId(),
        ticket.getRequestTypeId(),
        ticket.getCreatorId(),
        ticket.getStatus().name(),
        ticket.getDataRevision(),
        ticket.getCurrentRevisionId(),
        ticket.getDataJson(),
        revision.getId(),
        revision.getRevisionNo(),
        revision.getDataSnapshotJson(),
        revision.getSourceSchemaVersion(),
        revision.getSchemaChecksum(),
        revision.getSubmittedAt(),
        subjectRepository.findAllByTicketIdOrderByCreatedAtAsc(ticketId).stream()
            .map(
                subject ->
                    new TicketSubjectSnapshot(
                        subject.getSubjectType(),
                        subject.getSubjectRefId(),
                        subject.getRoleKey(),
                        subject.getSourceField()))
            .toList());
  }
}
