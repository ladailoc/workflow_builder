package com.fpt.workflow.definition.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.api.CommandConflictException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkflowPublishServiceIT {

  private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_publish_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowPublishService publishService;
  @Autowired private WorkflowValidationService validationService;
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
  void publishesDeterministicImmutablePackageAndAudits() {
    Fixture fixture = fixture("publish");
    String firstChecksum =
        validationService.compileCurrent(fixture.version().getId()).definitionChecksum();
    String secondChecksum =
        validationService.compileCurrent(fixture.version().getId()).definitionChecksum();

    WorkflowPublishService.PublishResult result =
        publishService.publish(
            fixture.version().getId(), new ExpectedVersion(0), 0, new CommandId(UUID.randomUUID()));

    WorkflowVersion published = versionRepository.findById(fixture.version().getId()).orElseThrow();
    WorkflowDefinition definition =
        definitionRepository.findById(fixture.definition().getId()).orElseThrow();
    assertThat(firstChecksum).isEqualTo(secondChecksum);
    assertThat(result.status()).isEqualTo(WorkflowVersionStatus.PUBLISHED);
    assertThat(published.getExecutionPackageJson().path("executionPackageSchemaVersion").asInt())
        .isEqualTo(1);
    assertThat(published.getChecksum()).isEqualTo(result.checksum()).hasSize(64);
    assertThat(definition.getCurrentPublishedVersionId()).isEqualTo(published.getId());
    assertThat(definition.getActiveDraftVersionId()).isNull();
    assertThat(
            auditRepository.findAllByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(
                "WORKFLOW_VERSION", published.getId()))
        .extracting(com.fpt.workflow.operations.audit.AuditEvent::getEventType)
        .containsExactly("WORKFLOW_VERSION_PUBLISHED");

    NodeDefinition node = fixture.nodes().getFirst();
    assertThatThrownBy(() -> nodeRepository.deleteById(node.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                publishService.publish(
                    published.getId(),
                    new ExpectedVersion(published.getLockVersion()),
                    0,
                    new CommandId(UUID.randomUUID())))
        .isInstanceOf(CommandConflictException.class);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void supersedesPreviousVersionWhileOldEventKeepsExactReference() {
    Fixture first = fixture("history");
    publishService.publish(
        first.version().getId(), new ExpectedVersion(0), 0, new CommandId(UUID.randomUUID()));
    Event oldEvent = eventBoundTo(first);

    Fixture second =
        nextDraft(definitionRepository.findById(first.definition().getId()).orElseThrow(), 2);
    publishService.publish(
        second.version().getId(), new ExpectedVersion(0), 0, new CommandId(UUID.randomUUID()));

    assertThat(versionRepository.findById(first.version().getId()).orElseThrow().getStatus())
        .isEqualTo(WorkflowVersionStatus.SUPERSEDED);
    assertThat(eventRepository.findById(oldEvent.getId()).orElseThrow().getWorkflowVersionId())
        .isEqualTo(first.version().getId());
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void serializesConcurrentPublishSoOnlyOneSucceeds() throws Exception {
    Fixture fixture = fixture("concurrent");
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Callable<Boolean> attempt =
        () -> {
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(authentication);
          SecurityContextHolder.setContext(context);
          try {
            publishService.publish(
                fixture.version().getId(),
                new ExpectedVersion(0),
                0,
                new CommandId(UUID.randomUUID()));
            return true;
          } catch (RuntimeException exception) {
            return false;
          } finally {
            SecurityContextHolder.clearContext();
          }
        };
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Boolean> outcomes =
          executor.invokeAll(List.of(attempt, attempt)).stream()
              .map(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception exception) {
                      throw new AssertionError(exception);
                    }
                  })
              .toList();
      assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    }
  }

  private Fixture fixture(String prefix) {
    WorkflowDefinition definition =
        definitionRepository.saveAndFlush(
            WorkflowDefinition.create(
                UUID.randomUUID(), key(prefix), "Workflow", null, ACTOR, ACTOR, Instant.now()));
    return nextDraft(definition, 1);
  }

  private Fixture nextDraft(WorkflowDefinition definition, int versionNo) {
    WorkflowVersion version =
        versionRepository.saveAndFlush(
            WorkflowVersion.createDraft(
                UUID.randomUUID(),
                definition.getId(),
                versionNo,
                null,
                null,
                ACTOR,
                Instant.now()));
    definition.assignActiveDraft(version.getId(), Instant.now());
    definitionRepository.saveAndFlush(definition);
    NodeDefinition start = node(version, "start", "START");
    NodeDefinition end = node(version, "end", "END");
    nodeRepository.saveAllAndFlush(List.of(start, end));
    EdgeDefinition edge =
        EdgeDefinition.create(
            UUID.randomUUID(),
            version.getId(),
            start.getId(),
            "STARTED",
            end.getId(),
            null,
            0,
            false,
            TransitionType.NORMAL,
            null,
            objectMapper.createObjectNode());
    edgeRepository.saveAndFlush(edge);
    return new Fixture(definition, version, List.of(start, end));
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

  private Event eventBoundTo(Fixture fixture) {
    RequestType requestType =
        requestTypeRepository.saveAndFlush(
            RequestType.create(
                UUID.randomUUID(),
                key("request"),
                "Request",
                null,
                "TEST",
                fixture.definition().getId(),
                true,
                objectMapper.createObjectNode(),
                Instant.now()));
    Ticket ticket =
        ticketRepository.saveAndFlush(
            Ticket.createDraft(
                UUID.randomUUID(),
                requestType.getId(),
                ACTOR,
                objectMapper.createObjectNode(),
                Instant.now()));
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
                Instant.now(),
                "submit"));
    ticket.submit(revisionId, 1, objectMapper.createObjectNode(), Instant.now());
    ticketRepository.saveAndFlush(ticket);
    return eventRepository.saveAndFlush(
        Event.createRoot(
            UUID.randomUUID(),
            ticket.getId(),
            fixture.version().getId(),
            revision.getId(),
            null,
            null,
            "TICKET_SUBMIT",
            UUID.randomUUID().toString(),
            objectMapper.createObjectNode(),
            ACTOR,
            Instant.now()));
  }

  private String key(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }

  private record Fixture(
      WorkflowDefinition definition, WorkflowVersion version, List<NodeDefinition> nodes) {}
}
