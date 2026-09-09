package com.fpt.workflow.definition.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.publish.WorkflowPublishService;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.domain.TicketRevision;
import com.fpt.workflow.ticket.repository.TicketRepository;
import com.fpt.workflow.ticket.repository.TicketRevisionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkflowRollbackIT {

  private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_rollback_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowRollbackService rollbackService;
  @Autowired private WorkflowSemanticDiffService diffService;
  @Autowired private WorkflowPublishService publishService;
  @Autowired private WorkflowDefinitionRepository definitionRepository;
  @Autowired private WorkflowVersionRepository versionRepository;
  @Autowired private NodeDefinitionRepository nodeRepository;
  @Autowired private EdgeDefinitionRepository edgeRepository;
  @Autowired private AuditEventRepository auditRepository;
  @Autowired private RequestTypeRepository requestTypeRepository;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private TicketRevisionRepository ticketRevisionRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void rollbackClonesKnownGoodVersionToMonotonicNewVersionWhileOldEventsStayBound() {
    Instant now = Instant.now();

    // 1. Create Workflow Definition
    WorkflowDefinition definition =
        definitionRepository.saveAndFlush(
            WorkflowDefinition.create(
                UUID.randomUUID(), key("wf"), "Expense Workflow", null, ACTOR, ACTOR, now));

    // 2. Create and Publish Version 1
    WorkflowVersion v1 =
        versionRepository.saveAndFlush(
            WorkflowVersion.createDraft(
                UUID.randomUUID(), definition.getId(), 1, null, null, ACTOR, now));
    definition.assignActiveDraft(v1.getId(), now);
    definitionRepository.saveAndFlush(definition);

    NodeDefinition v1Start = node(v1, "start", "START");
    NodeDefinition v1End = node(v1, "end", "END");
    nodeRepository.saveAllAndFlush(List.of(v1Start, v1End));
    EdgeDefinition v1Edge = edge(v1, v1Start.getId(), "STARTED", v1End.getId());
    edgeRepository.saveAndFlush(v1Edge);

    publishService.publish(v1.getId(), new ExpectedVersion(0), 0, new CommandId(UUID.randomUUID()));
    v1 = versionRepository.findById(v1.getId()).orElseThrow();
    assertThat(v1.getStatus()).isEqualTo(WorkflowVersionStatus.PUBLISHED);

    // 3. Start an Event bound to Version 1
    Event eventBoundToV1 = createEvent(definition, v1);
    assertThat(eventBoundToV1.getWorkflowVersionId()).isEqualTo(v1.getId());

    // 4. Create and Publish Version 2
    definition = definitionRepository.findById(definition.getId()).orElseThrow();
    WorkflowVersion v2 =
        versionRepository.saveAndFlush(
            WorkflowVersion.createDraft(
                UUID.randomUUID(), definition.getId(), 2, null, null, ACTOR, Instant.now()));
    definition.assignActiveDraft(v2.getId(), Instant.now());
    definitionRepository.saveAndFlush(definition);

    NodeDefinition v2Start = node(v2, "start", "START");
    NodeDefinition v2End =
        NodeDefinition.create(
            UUID.randomUUID(),
            v2.getId(),
            "end",
            "END",
            "Updated End",
            "Updated description",
            1,
            objectMapper.createObjectNode(),
            null,
            null,
            objectMapper.createObjectNode());
    nodeRepository.saveAllAndFlush(List.of(v2Start, v2End));
    EdgeDefinition v2Edge = edge(v2, v2Start.getId(), "STARTED", v2End.getId());
    edgeRepository.saveAndFlush(v2Edge);

    publishService.publish(v2.getId(), new ExpectedVersion(0), 0, new CommandId(UUID.randomUUID()));

    definition = definitionRepository.findById(definition.getId()).orElseThrow();
    assertThat(definition.getCurrentPublishedVersionId()).isEqualTo(v2.getId());

    // Verify v1 is now SUPERSEDED
    v1 = versionRepository.findById(v1.getId()).orElseThrow();
    assertThat(v1.getStatus()).isEqualTo(WorkflowVersionStatus.SUPERSEDED);
    String v1Checksum = v1.getChecksum();

    // 5. Rollback to known-good Version 1
    definition = definitionRepository.findById(definition.getId()).orElseThrow();
    WorkflowRollbackService.RollbackResult result =
        rollbackService.rollback(
            v1.getId(),
            new ExpectedVersion(definition.getLockVersion()),
            new CommandId(UUID.randomUUID()));

    // Verify monotonic new version 3 was produced
    assertThat(result.versionNo()).isEqualTo(3);
    assertThat(result.sourceVersionId()).isEqualTo(v1.getId());

    definition = definitionRepository.findById(definition.getId()).orElseThrow();
    assertThat(definition.getCurrentPublishedVersionId()).isEqualTo(result.publishedVersionId());
    assertThat(definition.getActiveDraftVersionId()).isNull();

    WorkflowVersion v3 = versionRepository.findById(result.publishedVersionId()).orElseThrow();
    assertThat(v3.getStatus()).isEqualTo(WorkflowVersionStatus.PUBLISHED);
    assertThat(v3.getVersionNo()).isEqualTo(3);
    assertThat(v3.getChecksum()).isNotBlank();

    // Semantic diff between v1 and v3 shows no functional changes
    WorkflowSemanticDiffService.SemanticDiff diffWithV1 = diffService.diff(v1.getId(), v3.getId());
    assertThat(diffWithV1.hasChanges()).isFalse();

    // Verify Version 1 was NOT mutated
    v1 = versionRepository.findById(v1.getId()).orElseThrow();
    assertThat(v1.getStatus()).isEqualTo(WorkflowVersionStatus.SUPERSEDED);
    assertThat(v1.getVersionNo()).isEqualTo(1);
    assertThat(v1.getChecksum()).isEqualTo(v1Checksum);

    // Verify existing Event on Version 1 remains strictly bound to Version 1
    Event refreshedEvent = eventRepository.findById(eventBoundToV1.getId()).orElseThrow();
    assertThat(refreshedEvent.getWorkflowVersionId()).isEqualTo(v1.getId());
  }

  private Event createEvent(WorkflowDefinition definition, WorkflowVersion version) {
    Instant now = Instant.now();
    RequestType requestType =
        requestTypeRepository.saveAndFlush(
            RequestType.create(
                UUID.randomUUID(),
                key("req"),
                "Request Type",
                null,
                "GENERAL",
                definition.getId(),
                true,
                objectMapper.createObjectNode(),
                now));

    Ticket ticket =
        ticketRepository.saveAndFlush(
            Ticket.createDraft(
                UUID.randomUUID(),
                requestType.getId(),
                ACTOR,
                objectMapper.createObjectNode(),
                now));

    UUID revisionId = UUID.randomUUID();
    TicketRevision revision =
        ticketRevisionRepository.saveAndFlush(
            TicketRevision.create(
                revisionId,
                ticket.getId(),
                1,
                objectMapper.createObjectNode(),
                "1",
                "checksum",
                ACTOR,
                now,
                "Initial submit"));

    ticket.submit(revisionId, 1, objectMapper.createObjectNode(), now);
    ticketRepository.saveAndFlush(ticket);

    return eventRepository.saveAndFlush(
        Event.createRoot(
            UUID.randomUUID(),
            ticket.getId(),
            version.getId(),
            revision.getId(),
            null,
            null,
            "TICKET_SUBMIT",
            UUID.randomUUID().toString(),
            objectMapper.createObjectNode(),
            ACTOR,
            now));
  }

  private NodeDefinition node(WorkflowVersion version, String key, String type) {
    return NodeDefinition.create(
        UUID.randomUUID(),
        version.getId(),
        key,
        type,
        key,
        null,
        1,
        objectMapper.createObjectNode(),
        null,
        null,
        objectMapper.createObjectNode());
  }

  private EdgeDefinition edge(
      WorkflowVersion version, UUID sourceId, String sourcePort, UUID targetId) {
    return EdgeDefinition.create(
        UUID.randomUUID(),
        version.getId(),
        sourceId,
        sourcePort,
        targetId,
        null,
        0,
        false,
        TransitionType.NORMAL,
        null,
        objectMapper.createObjectNode());
  }

  private String key(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }
}
