package com.fpt.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.ValidationSeverity;
import com.fpt.workflow.definition.domain.VariableScope;
import com.fpt.workflow.definition.domain.WorkflowValidationIssue;
import com.fpt.workflow.definition.domain.WorkflowValidationRun;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.definition.dto.WorkflowDefinitionDtos;
import com.fpt.workflow.definition.dto.WorkflowValidationDtos;
import com.fpt.workflow.definition.dto.WorkflowVariableDtos;
import com.fpt.workflow.definition.dto.WorkflowVersionDtos;
import com.fpt.workflow.definition.repository.WorkflowValidationIssueRepository;
import com.fpt.workflow.definition.repository.WorkflowValidationRunRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.definition.service.WorkflowDefinitionService;
import com.fpt.workflow.definition.service.WorkflowVersionService;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.dto.WorkflowFormDtos;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkflowContractPersistenceIT {

  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final Instant VALIDATED_AT = Instant.parse("2026-09-07T08:00:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_contract_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowDefinitionService workflowDefinitionService;
  @Autowired private WorkflowVersionService workflowVersionService;
  @Autowired private WorkflowFormRepository workflowFormRepository;
  @Autowired private WorkflowVariableRepository workflowVariableRepository;
  @Autowired private WorkflowValidationRunRepository validationRunRepository;
  @Autowired private WorkflowValidationIssueRepository validationIssueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EntityManager entityManager;

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void migratesAndPersistsFormVariableAndValidationContracts() {
    DraftFixture fixture = createDraft("contracts");
    Integer appliedMigration =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success",
            Integer.class);

    WorkflowForm ticketForm =
        workflowFormRepository.saveAndFlush(
            form(fixture.draft().id(), "ticket", WorkflowFormType.TICKET_FORM));
    WorkflowForm taskForm =
        workflowFormRepository.saveAndFlush(
            form(fixture.draft().id(), "approval", WorkflowFormType.TASK_FORM));
    WorkflowVariable variable =
        workflowVariableRepository.saveAndFlush(variable(fixture.draft().id(), "riskLevel"));
    WorkflowValidationRun run =
        validationRunRepository.saveAndFlush(
            WorkflowValidationRun.create(
                UUID.randomUUID(),
                fixture.draft().id(),
                fixture.draft().revision(),
                "sha256-definition",
                true,
                false,
                0,
                1,
                1,
                ACTOR_ID,
                VALIDATED_AT));
    WorkflowValidationIssue issue =
        validationIssueRepository.saveAndFlush(
            WorkflowValidationIssue.create(
                UUID.randomUUID(),
                run.getId(),
                "NODE.MISSING_END",
                ValidationSeverity.ACK_REQUIRED_WARNING,
                "WORKFLOW_NODE",
                UUID.randomUUID(),
                "/config/outcome",
                "An explicit end route is recommended",
                "Connect the terminal outcome",
                objectMapper.createObjectNode().put("acknowledgementRequired", true)));

    assertThat(appliedMigration).isEqualTo(1);
    assertThat(
            workflowFormRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(
                fixture.draft().id()))
        .extracting(WorkflowForm::getFormKey)
        .containsExactly("approval", "ticket");
    assertThat(WorkflowFormDtos.View.from(ticketForm).formType())
        .isEqualTo(WorkflowFormType.TICKET_FORM);
    assertThat(WorkflowFormDtos.View.from(taskForm).formType())
        .isEqualTo(WorkflowFormType.TASK_FORM);
    assertThat(WorkflowVariableDtos.View.from(variable).defaultJson().asText()).isEqualTo("LOW");
    assertThat(WorkflowValidationDtos.RunView.from(run).warningCount()).isEqualTo(1);
    assertThat(WorkflowValidationDtos.IssueView.from(issue).severity())
        .isEqualTo(ValidationSeverity.ACK_REQUIRED_WARNING);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void enforcesUniqueKeysCanonicalEnumsJsonShapesAndValidationSummaries() {
    DraftFixture fixture = createDraft("constraints");
    WorkflowForm savedForm =
        workflowFormRepository.saveAndFlush(
            form(fixture.draft().id(), "ticket", WorkflowFormType.TICKET_FORM));
    WorkflowVariable savedVariable =
        workflowVariableRepository.saveAndFlush(variable(fixture.draft().id(), "riskLevel"));
    WorkflowValidationRun run =
        validationRunRepository.saveAndFlush(validRun(fixture.draft().id()));

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_forms "
                        + "(id, workflow_version_id, form_key, form_type, schema_json, schema_checksum) "
                        + "VALUES (?, ?, 'ticket', 'TASK_FORM', '{}'::jsonb, 'duplicate')",
                    UUID.randomUUID(),
                    fixture.draft().id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_variables "
                        + "(id, workflow_version_id, key, type, scope, mutable, sensitive) "
                        + "VALUES (?, ?, 'riskLevel', 'STRING', 'EVENT', true, false)",
                    UUID.randomUUID(),
                    fixture.draft().id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_forms "
                        + "(id, workflow_version_id, form_key, form_type, schema_json, schema_checksum) "
                        + "VALUES (?, ?, 'badForm', 'RUNTIME_FORM', '[]'::jsonb, '')",
                    UUID.randomUUID(),
                    fixture.draft().id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_variables "
                        + "(id, workflow_version_id, key, type, scope, mutable, sensitive) "
                        + "VALUES (?, ?, 'badVariable', 'JAVA_OBJECT', 'GLOBAL', true, false)",
                    UUID.randomUUID(),
                    fixture.draft().id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_validation_runs "
                        + "(id, workflow_version_id, revision, definition_checksum, valid, publishable, "
                        + "error_count, warning_count, info_count, validated_by, validated_at) "
                        + "VALUES (?, ?, 0, 'invalid-summary', false, true, 0, 0, 0, ?, now())",
                    UUID.randomUUID(),
                    fixture.draft().id(),
                    ACTOR_ID))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_validation_issues "
                        + "(id, validation_run_id, rule_code, severity, resource_type, resource_id, "
                        + "message, metadata_json) VALUES (?, ?, 'BAD.CODE', 'CRITICAL', "
                        + "'WORKFLOW_VERSION', ?, 'Bad severity', '{}'::jsonb)",
                    UUID.randomUUID(),
                    run.getId(),
                    fixture.draft().id()))
        .isInstanceOf(DataAccessException.class);

    assertThat(workflowFormRepository.existsById(savedForm.getId())).isTrue();
    assertThat(workflowVariableRepository.existsById(savedVariable.getId())).isTrue();
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void persistsRecursiveCanonicalDescriptorsAndRejectsInvalidDatabaseShapes() {
    DraftFixture fixture = createDraft("canonical-types");
    TypeDescriptor descriptor =
        TypeDescriptor.nullableArrayOf(TypeDescriptor.nullable(CanonicalValueType.USER_ID));
    UUID userId = UUID.randomUUID();
    WorkflowVariable saved =
        workflowVariableRepository.saveAndFlush(
            WorkflowVariable.create(
                UUID.randomUUID(),
                fixture.draft().id(),
                "reviewers",
                descriptor,
                VariableScope.EVENT,
                objectMapper.createArrayNode().add(userId.toString()).addNull(),
                true,
                false));
    entityManager.clear();

    Integer appliedMigration =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '9' AND success",
            Integer.class);
    WorkflowVariable restored = workflowVariableRepository.findById(saved.getId()).orElseThrow();

    assertThat(appliedMigration).isEqualTo(1);
    assertThat(restored.getType()).isEqualTo(descriptor);
    assertThat(restored.getDefaultJson()).hasSize(2);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_variables "
                        + "(id, workflow_version_id, key, type_descriptor_json, scope, mutable, sensitive) "
                        + "VALUES (?, ?, 'legacyList', CAST(? AS jsonb), 'EVENT', true, false)",
                    UUID.randomUUID(),
                    fixture.draft().id(),
                    "{\"type\":\"USER_LIST\",\"nullable\":false}"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_variables "
                        + "(id, workflow_version_id, key, type_descriptor_json, scope, mutable, sensitive) "
                        + "VALUES (?, ?, 'rawArray', CAST(? AS jsonb), 'EVENT', true, false)",
                    UUID.randomUUID(),
                    fixture.draft().id(),
                    "{\"type\":\"ARRAY\",\"nullable\":false}"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void enforcesWorkflowVersionOwnershipAndCascadesWithDeletedDraft() {
    DraftFixture fixture = createDraft("ownership");
    WorkflowForm form =
        workflowFormRepository.saveAndFlush(
            form(fixture.draft().id(), "ticket", WorkflowFormType.TICKET_FORM));
    WorkflowVariable variable =
        workflowVariableRepository.saveAndFlush(variable(fixture.draft().id(), "riskLevel"));
    WorkflowValidationRun run =
        validationRunRepository.saveAndFlush(validRun(fixture.draft().id()));
    WorkflowValidationIssue issue =
        validationIssueRepository.saveAndFlush(
            WorkflowValidationIssue.create(
                UUID.randomUUID(),
                run.getId(),
                "FORM.REQUIRED_FIELD",
                ValidationSeverity.INFO,
                "WORKFLOW_FORM",
                form.getId(),
                "/fields/0",
                "Field is required",
                null,
                objectMapper.createObjectNode()));

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_forms "
                        + "(id, workflow_version_id, form_key, form_type, schema_json, schema_checksum) "
                        + "VALUES (?, ?, 'orphan', 'TICKET_FORM', '{}'::jsonb, 'sha256')",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_validation_issues "
                        + "(id, validation_run_id, rule_code, severity, resource_type, resource_id, "
                        + "message, metadata_json) VALUES (?, ?, 'ORPHAN.ISSUE', 'ERROR', "
                        + "'WORKFLOW_VERSION', ?, 'Orphan', '{}'::jsonb)",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    fixture.draft().id()))
        .isInstanceOf(DataAccessException.class);

    workflowVersionService.deleteDraft(
        fixture.draft().id(), new ExpectedVersion(fixture.draft().lockVersion()));

    assertThat(workflowFormRepository.existsById(form.getId())).isFalse();
    assertThat(workflowVariableRepository.existsById(variable.getId())).isFalse();
    assertThat(validationRunRepository.existsById(run.getId())).isFalse();
    assertThat(validationIssueRepository.existsById(issue.getId())).isFalse();
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void blocksFormAndVariableMutationAfterWorkflowVersionIsPublished() {
    DraftFixture fixture = createDraft("published-contract");
    WorkflowForm form =
        workflowFormRepository.saveAndFlush(
            form(fixture.draft().id(), "ticket", WorkflowFormType.TICKET_FORM));
    WorkflowVariable variable =
        workflowVariableRepository.saveAndFlush(variable(fixture.draft().id(), "riskLevel"));

    jdbcTemplate.update(
        "UPDATE workflow_definitions SET active_draft_version_id = NULL WHERE id = ?",
        fixture.definitionId());
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'PUBLISHED', checksum = 'sha256-published', "
            + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now(), "
            + "lock_version = lock_version + 1 WHERE id = ?",
        ACTOR_ID,
        fixture.draft().id());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE workflow_forms SET schema_checksum = 'mutated' WHERE id = ?",
                    form.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM workflow_variables WHERE id = ?", variable.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_forms "
                        + "(id, workflow_version_id, form_key, form_type, schema_json, schema_checksum) "
                        + "VALUES (?, ?, 'lateForm', 'TASK_FORM', '{}'::jsonb, 'late')",
                    UUID.randomUUID(),
                    fixture.draft().id()))
        .isInstanceOf(DataAccessException.class);
  }

  private DraftFixture createDraft(String prefix) {
    WorkflowDefinitionDtos.View definition =
        workflowDefinitionService.create(
            new WorkflowDefinitionDtos.Create(
                uniqueKey(prefix), "Workflow " + prefix, null, UUID.randomUUID()));
    WorkflowVersionDtos.View draft =
        workflowVersionService.createDraft(
            new WorkflowVersionDtos.CreateDraft(definition.id(), null, null));
    return new DraftFixture(definition.id(), draft);
  }

  private WorkflowForm form(UUID versionId, String formKey, WorkflowFormType formType) {
    return WorkflowForm.create(
        UUID.randomUUID(),
        versionId,
        formKey,
        formType,
        objectMapper.createObjectNode().put("type", "object"),
        "sha256-" + formKey);
  }

  private WorkflowVariable variable(UUID versionId, String key) {
    return WorkflowVariable.create(
        UUID.randomUUID(),
        versionId,
        key,
        TypeDescriptor.required(CanonicalValueType.STRING),
        VariableScope.EVENT,
        objectMapper.getNodeFactory().textNode("LOW"),
        true,
        false);
  }

  private WorkflowValidationRun validRun(UUID versionId) {
    return WorkflowValidationRun.create(
        UUID.randomUUID(),
        versionId,
        0,
        "sha256-definition",
        true,
        true,
        0,
        0,
        0,
        ACTOR_ID,
        VALIDATED_AT);
  }

  private static String uniqueKey(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private record DraftFixture(UUID definitionId, WorkflowVersionDtos.View draft) {}
}
