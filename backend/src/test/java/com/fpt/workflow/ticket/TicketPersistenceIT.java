package com.fpt.workflow.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.form.engine.FieldEditability;
import com.fpt.workflow.form.engine.FieldRequirement;
import com.fpt.workflow.form.engine.FieldSemanticMetadata;
import com.fpt.workflow.form.engine.FieldValidationRules;
import com.fpt.workflow.form.engine.FieldVisibility;
import com.fpt.workflow.form.engine.FormFieldDefinition;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.StaleAggregateVersionException;
import com.fpt.workflow.shared.domain.lifecycle.TicketStatus;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.dto.TicketDtos;
import com.fpt.workflow.ticket.repository.TicketRepository;
import com.fpt.workflow.ticket.service.TicketService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "USER")
class TicketPersistenceIT {

  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_ticket_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private TicketService ticketService;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void numbersRevisionsAndAdvancesTheCurrentImmutableSnapshot() {
    UUID requestTypeId = activeRequestType();
    UUID employeeId = UUID.randomUUID();
    TicketDtos.AggregateView draft =
        ticketService.createDraft(
            new TicketDtos.CreateDraft(
                requestTypeId,
                objectMapper.createObjectNode().put("amount", 100),
                List.of(subject(employeeId))));

    TicketDtos.AggregateView submitted =
        ticketService.submit(
            draft.ticket().id(),
            new ExpectedVersion(draft.ticket().lockVersion()),
            submitRequest(requestTypeId, "Initial submission"));
    TicketDtos.AggregateView revised =
        ticketService.recordBusinessRevision(
            draft.ticket().id(),
            new ExpectedVersion(submitted.ticket().lockVersion()),
            revisionRequest(
                requestTypeId,
                objectMapper.createObjectNode().put("amount", 125),
                "Requester corrected amount",
                List.of(subject(employeeId))));

    assertThat(submitted.ticket().status()).isEqualTo(TicketStatus.SUBMITTED);
    assertThat(revised.ticket().dataRevision()).isEqualTo(2);
    assertThat(revised.ticket().dataJson().path("amount").asInt()).isEqualTo(125);
    assertThat(revised.revisions())
        .extracting(TicketDtos.RevisionView::revisionNo)
        .containsExactly(1L, 2L);
    assertThat(revised.revisions())
        .extracting(TicketDtos.RevisionView::submittedBy)
        .containsOnly(ACTOR_ID);
    assertThat(revised.ticket().currentRevisionId()).isEqualTo(revised.revisions().get(1).id());
  }

  @Test
  void databaseRejectsRevisionMutationDuplicateNumberAndCrossTicketPointer() {
    UUID requestTypeId = activeRequestType();
    TicketDtos.AggregateView first = createAndSubmit(requestTypeId);
    TicketDtos.RevisionView revision = first.revisions().getFirst();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ticket_revisions SET data_snapshot_json = '{}'::jsonb WHERE id = ?",
                    revision.id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM ticket_revisions WHERE id = ?", revision.id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO ticket_revisions "
                        + "(id, ticket_id, revision_no, data_snapshot_json, source_schema_version, "
                        + "schema_checksum, submitted_by, submitted_at) "
                        + "VALUES (?, ?, 1, '{}'::jsonb, 'v1', 'checksum', ?, now())",
                    UUID.randomUUID(),
                    first.ticket().id(),
                    ACTOR_ID))
        .isInstanceOf(DataAccessException.class);

    TicketDtos.AggregateView second =
        ticketService.createDraft(
            new TicketDtos.CreateDraft(requestTypeId, objectMapper.createObjectNode(), List.of()));
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE tickets SET status = 'SUBMITTED', data_revision = 1, "
                        + "current_revision_id = ?, submitted_at = now() WHERE id = ?",
                    revision.id(),
                    second.ticket().id()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void enforcesExpectedAndJpaOptimisticVersions() {
    UUID requestTypeId = activeRequestType();
    TicketDtos.AggregateView draft =
        ticketService.createDraft(
            new TicketDtos.CreateDraft(
                requestTypeId, objectMapper.createObjectNode().put("value", 1), List.of()));
    TicketDtos.AggregateView updated =
        ticketService.updateDraft(
            draft.ticket().id(),
            new ExpectedVersion(draft.ticket().lockVersion()),
            new TicketDtos.UpdateDraft(objectMapper.createObjectNode().put("value", 2), List.of()));

    assertThat(updated.ticket().lockVersion()).isEqualTo(draft.ticket().lockVersion() + 1);
    assertThatThrownBy(
            () ->
                ticketService.updateDraft(
                    draft.ticket().id(),
                    new ExpectedVersion(draft.ticket().lockVersion()),
                    new TicketDtos.UpdateDraft(
                        objectMapper.createObjectNode().put("value", 3), List.of())))
        .isInstanceOf(StaleAggregateVersionException.class);

    Ticket firstCopy = ticketRepository.findById(draft.ticket().id()).orElseThrow();
    Ticket staleCopy = ticketRepository.findById(draft.ticket().id()).orElseThrow();
    firstCopy.updateDraft(
        objectMapper.createObjectNode().put("value", 4), firstCopy.getUpdatedAt().plusSeconds(1));
    ticketRepository.saveAndFlush(firstCopy);
    staleCopy.updateDraft(
        objectMapper.createObjectNode().put("value", 5), staleCopy.getUpdatedAt().plusSeconds(1));

    assertThatThrownBy(() -> ticketRepository.saveAndFlush(staleCopy))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void persistsNormalizedSubjectsWithoutCustomFieldOrParticipantStorage() {
    UUID requestTypeId = activeRequestType();
    UUID employeeOne = UUID.randomUUID();
    UUID employeeTwo = UUID.randomUUID();
    var dataJson = objectMapper.createObjectNode();
    dataJson.putArray("evaluationTargets").add(employeeOne.toString()).add(employeeTwo.toString());
    TicketDtos.AggregateView aggregate =
        ticketService.createDraft(
            new TicketDtos.CreateDraft(
                requestTypeId, dataJson, List.of(subject(employeeOne), subject(employeeTwo))));

    assertThat(aggregate.subjects()).hasSize(2);
    assertThat(aggregate.subjects())
        .extracting(TicketDtos.SubjectView::subjectRefId)
        .containsExactlyInAnyOrder(employeeOne, employeeTwo);
    assertThat(aggregate.subjects())
        .allSatisfy(
            subject -> {
              assertThat(subject.subjectType()).isEqualTo("EMPLOYEE");
              assertThat(subject.roleKey()).isEqualTo("EVALUATION_TARGET");
              assertThat(subject.sourceField()).isEqualTo("evaluationTargets");
            });
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.ticket_custom_fields')", String.class))
        .isNull();

    assertThatThrownBy(
            () ->
                ticketService.updateDraft(
                    aggregate.ticket().id(),
                    new ExpectedVersion(aggregate.ticket().lockVersion()),
                    new TicketDtos.UpdateDraft(
                        aggregate.ticket().dataJson(),
                        List.of(subject(employeeOne), subject(employeeOne)))))
        .isInstanceOf(CommandConflictException.class);
  }

  @Test
  void deniesTicketAccessToAnUnrelatedAuthenticatedActor() {
    UUID requestTypeId = activeRequestType();
    UUID ticketId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tickets "
            + "(id, request_type_id, creator_id, status, data_json, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'DRAFT', '{}'::jsonb, now(), now())",
        ticketId,
        requestTypeId,
        UUID.randomUUID());

    assertThatThrownBy(() -> ticketService.get(ticketId)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void rejectsUnknownTicketFieldsAndStaleClientFormContract() {
    UUID requestTypeId = activeRequestType();

    assertThatThrownBy(
            () ->
                ticketService.createDraft(
                    new TicketDtos.CreateDraft(
                        requestTypeId,
                        objectMapper.createObjectNode().put("creatorInventedField", "forbidden"),
                        List.of())))
        .isInstanceOf(com.fpt.workflow.shared.api.UnprocessableCommandException.class)
        .hasMessageContaining("FORM.UNKNOWN_FIELD");

    TicketDtos.AggregateView draft =
        ticketService.createDraft(
            new TicketDtos.CreateDraft(
                requestTypeId, objectMapper.createObjectNode().put("amount", 10), List.of()));
    TicketFormRef current = currentForm(requestTypeId);
    assertThatThrownBy(
            () ->
                ticketService.submit(
                    draft.ticket().id(),
                    new ExpectedVersion(draft.ticket().lockVersion()),
                    new TicketDtos.Submit(current.workflowVersionId(), "stale-checksum", "Submit")))
        .isInstanceOf(CommandConflictException.class)
        .hasMessageContaining("differs from the form loaded by the client");
  }

  private TicketDtos.AggregateView createAndSubmit(UUID requestTypeId) {
    TicketDtos.AggregateView draft =
        ticketService.createDraft(
            new TicketDtos.CreateDraft(
                requestTypeId, objectMapper.createObjectNode().put("amount", 10), List.of()));
    return ticketService.submit(
        draft.ticket().id(),
        new ExpectedVersion(draft.ticket().lockVersion()),
        submitRequest(requestTypeId, null));
  }

  private TicketDtos.SubjectInput subject(UUID subjectRefId) {
    return new TicketDtos.SubjectInput(
        "EMPLOYEE", subjectRefId, "EVALUATION_TARGET", "evaluationTargets");
  }

  private UUID activeRequestType() {
    UUID definitionId = UUID.randomUUID();
    UUID workflowVersionId = UUID.randomUUID();
    UUID requestTypeId = UUID.randomUUID();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions "
            + "(id, key, name, lifecycle, owner_id, created_by, created_at, updated_at) "
            + "VALUES (?, ?, 'Ticket workflow', 'ACTIVE', ?, ?, now(), now())",
        definitionId,
        "ticket-" + suffix,
        ACTOR_ID,
        ACTOR_ID);
    jdbcTemplate.update(
        "INSERT INTO workflow_versions "
            + "(id, definition_id, version_no, status, created_by, created_at) "
            + "VALUES (?, ?, 1, 'DRAFT', ?, now())",
        workflowVersionId,
        definitionId,
        ACTOR_ID);
    FormSchema schema =
        FormSchema.ticketForm(
            "ticket",
            List.of(
                formField("amount", 0, TypeDescriptor.nullable(CanonicalValueType.INTEGER), false),
                formField("value", 1, TypeDescriptor.nullable(CanonicalValueType.INTEGER), false),
                formField(
                    "evaluationTargets",
                    2,
                    TypeDescriptor.nullableArrayOf(
                        TypeDescriptor.required(CanonicalValueType.USER_ID)),
                    true)));
    jdbcTemplate.update(
        "INSERT INTO workflow_forms "
            + "(id, workflow_version_id, form_key, form_type, schema_json, schema_checksum) "
            + "VALUES (?, ?, 'ticket', 'TICKET_FORM', ?::jsonb, 'ticket-form-checksum')",
        UUID.randomUUID(),
        workflowVersionId,
        objectMapper.valueToTree(schema).toString());
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'package-checksum', "
            + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now() "
            + "WHERE id = ?",
        ACTOR_ID,
        workflowVersionId);
    jdbcTemplate.update(
        "UPDATE workflow_definitions SET current_published_version_id = ? WHERE id = ?",
        workflowVersionId,
        definitionId);
    jdbcTemplate.update(
        "INSERT INTO request_types "
            + "(id, key, name, category, workflow_definition_id, active, "
            + "creation_policy_json, created_at, updated_at) "
            + "VALUES (?, ?, 'Ticket request', 'GENERAL', ?, true, '{}'::jsonb, now(), now())",
        requestTypeId,
        "request-" + suffix,
        definitionId);
    return requestTypeId;
  }

  private TicketDtos.Submit submitRequest(UUID requestTypeId, String reason) {
    TicketFormRef form = currentForm(requestTypeId);
    return new TicketDtos.Submit(form.workflowVersionId(), form.schemaChecksum(), reason);
  }

  private TicketDtos.RecordRevision revisionRequest(
      UUID requestTypeId, JsonNode data, String reason, List<TicketDtos.SubjectInput> subjects) {
    TicketFormRef form = currentForm(requestTypeId);
    return new TicketDtos.RecordRevision(
        data, form.workflowVersionId(), form.schemaChecksum(), reason, subjects);
  }

  private TicketFormRef currentForm(UUID requestTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT definition.current_published_version_id, form.schema_checksum "
            + "FROM request_types request_type "
            + "JOIN workflow_definitions definition "
            + "ON definition.id = request_type.workflow_definition_id "
            + "JOIN workflow_forms form "
            + "ON form.workflow_version_id = definition.current_published_version_id "
            + "AND form.form_type = 'TICKET_FORM' "
            + "WHERE request_type.id = ?",
        (resultSet, rowNum) ->
            new TicketFormRef(resultSet.getObject(1, UUID.class), resultSet.getString(2)),
        requestTypeId);
  }

  private FormFieldDefinition formField(
      String key, int order, TypeDescriptor type, boolean businessSubject) {
    return new FormFieldDefinition(
        UUID.randomUUID(),
        key,
        key,
        null,
        null,
        order,
        type,
        null,
        false,
        FieldRequirement.never(),
        FieldVisibility.always(),
        FieldEditability.editable(),
        FieldValidationRules.none(),
        null,
        new FieldSemanticMetadata(false, businessSubject, false, false, false));
  }

  private record TicketFormRef(UUID workflowVersionId, String schemaChecksum) {}
}
