package com.fpt.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.dto.RequestTypeDtos;
import com.fpt.workflow.definition.dto.WorkflowDefinitionDtos;
import com.fpt.workflow.definition.dto.WorkflowVersionDtos;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.service.RequestTypeService;
import com.fpt.workflow.definition.service.WorkflowDefinitionService;
import com.fpt.workflow.definition.service.WorkflowVersionService;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.page.PageRequest;
import com.fpt.workflow.shared.domain.page.SortOrder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DefinitionPersistenceIT {

  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_definition_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowDefinitionService workflowDefinitionService;
  @Autowired private WorkflowVersionService workflowVersionService;
  @Autowired private RequestTypeService requestTypeService;
  @Autowired private WorkflowDefinitionRepository workflowDefinitionRepository;
  @Autowired private RequestTypeRepository requestTypeRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @WithMockActor(actorId = "10000000-0000-4000-8000-000000000001", roles = "WORKFLOW_OWNER")
  void migratesAndPersistsRequestTypeDefinitionAndDraftVersion() {
    Integer appliedMigration =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success",
            Integer.class);
    WorkflowDefinitionDtos.View definition = createDefinition("purchase");
    WorkflowVersionDtos.View draft =
        workflowVersionService.createDraft(
            new WorkflowVersionDtos.CreateDraft(definition.id(), null, null));
    WorkflowVersionDtos.View updatedDraft =
        workflowVersionService.updateDraft(
            draft.id(),
            new ExpectedVersion(draft.lockVersion()),
            new WorkflowVersionDtos.UpdateDraft(
                draft.revision(),
                "draft-checksum",
                objectMapper.createObjectNode().put("schemaVersion", 1)));
    RequestTypeDtos.View requestType =
        requestTypeService.create(
            new RequestTypeDtos.Create(
                uniqueKey("request"),
                "Purchase request",
                "Purchase catalog entry",
                "PROCUREMENT",
                definition.id(),
                true,
                objectMapper.createObjectNode().put("creator", "USER")));

    assertThat(appliedMigration).isEqualTo(1);
    assertThat(workflowDefinitionService.get(definition.id()).activeDraftVersionId())
        .isEqualTo(draft.id());
    assertThat(updatedDraft.revision()).isEqualTo(1);
    assertThat(updatedDraft.executionPackageJson().path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(requestType.workflowDefinitionId()).isEqualTo(definition.id());
    assertThat(definition.createdBy()).isEqualTo(ACTOR_ID);
    assertThat(
            workflowVersionService
                .listByDefinition(
                    definition.id(),
                    new PageRequest(0, 20, List.of(SortOrder.descending("versionNo"))))
                .items())
        .extracting(WorkflowVersionDtos.View::id)
        .containsExactly(draft.id());

    assertThatThrownBy(
            () ->
                workflowVersionService.updateDraft(
                    draft.id(),
                    new ExpectedVersion(updatedDraft.lockVersion()),
                    new WorkflowVersionDtos.UpdateDraft(
                        0, "stale", objectMapper.createObjectNode())))
        .isInstanceOf(CommandConflictException.class)
        .hasMessageContaining("current revision is 1");
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void enforcesUniqueKeysVersionNumbersAndOneActiveDraft() {
    WorkflowDefinitionDtos.View definition = createDefinition("unique");
    WorkflowVersionDtos.View draft =
        workflowVersionService.createDraft(
            new WorkflowVersionDtos.CreateDraft(definition.id(), null, null));

    assertThat(draft.versionNo()).isEqualTo(1);

    WorkflowDefinition duplicateDefinition =
        WorkflowDefinition.create(
            UUID.randomUUID(),
            definition.key(),
            "Duplicate",
            null,
            UUID.randomUUID(),
            ACTOR_ID,
            Instant.now());
    assertThatThrownBy(() -> workflowDefinitionRepository.saveAndFlush(duplicateDefinition))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                workflowVersionService.createDraft(
                    new WorkflowVersionDtos.CreateDraft(definition.id(), null, null)))
        .isInstanceOf(CommandConflictException.class)
        .hasMessageContaining("active draft");

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_versions "
                        + "(id, definition_id, version_no, status, revision, created_by, created_at, lock_version) "
                        + "VALUES (?, ?, ?, 'ARCHIVED', 0, ?, now(), 0)",
                    UUID.randomUUID(),
                    definition.id(),
                    draft.versionNo(),
                    ACTOR_ID))
        .isInstanceOf(DataIntegrityViolationException.class);

    RequestTypeDtos.View requestType = createRequestType(definition.id(), "unique-request");
    RequestType duplicateRequestType =
        RequestType.create(
            UUID.randomUUID(),
            requestType.key(),
            "Duplicate request",
            null,
            "PROCUREMENT",
            definition.id(),
            true,
            objectMapper.createObjectNode(),
            Instant.now());
    assertThatThrownBy(() -> requestTypeRepository.saveAndFlush(duplicateRequestType))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void enforcesSameDefinitionPointersAndRestrictiveDeletes() {
    WorkflowDefinitionDtos.View first = createDefinition("pointer-a");
    WorkflowDefinitionDtos.View second = createDefinition("pointer-b");
    WorkflowVersionDtos.View secondDraft =
        workflowVersionService.createDraft(
            new WorkflowVersionDtos.CreateDraft(second.id(), null, null));
    createRequestType(first.id(), "pointer-request");

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE workflow_definitions SET active_draft_version_id = ? WHERE id = ?",
                    secondDraft.id(),
                    first.id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM workflow_definitions WHERE id = ?", first.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void enforcesOptimisticLockingAndPublishedVersionImmutability() {
    WorkflowDefinitionDtos.View definition = createDefinition("locking");
    RequestTypeDtos.View requestType = createRequestType(definition.id(), "locking-request");

    RequestType firstCopy = requestTypeRepository.findById(requestType.id()).orElseThrow();
    RequestType staleCopy = requestTypeRepository.findById(requestType.id()).orElseThrow();
    Instant updateTime = firstCopy.getCreatedAt().plusSeconds(1);
    firstCopy.update(
        "First update",
        null,
        firstCopy.getCategory(),
        definition.id(),
        true,
        firstCopy.getCreationPolicyJson(),
        updateTime);
    requestTypeRepository.saveAndFlush(firstCopy);
    staleCopy.update(
        "Stale update",
        null,
        staleCopy.getCategory(),
        definition.id(),
        true,
        staleCopy.getCreationPolicyJson(),
        updateTime);

    assertThatThrownBy(() -> requestTypeRepository.saveAndFlush(staleCopy))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    UUID publishedId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workflow_versions "
            + "(id, definition_id, version_no, status, revision, checksum, execution_package_json, "
            + "created_by, created_at, published_by, published_at, lock_version) "
            + "VALUES (?, ?, 1, 'PUBLISHED', 0, 'sha256-value', CAST(? AS jsonb), ?, now(), ?, now(), 0)",
        publishedId,
        definition.id(),
        "{\"schemaVersion\":1}",
        ACTOR_ID,
        ACTOR_ID);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE workflow_versions SET checksum = 'mutated' WHERE id = ?", publishedId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM workflow_versions WHERE id = ?", publishedId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void generatesWorkflowVersionNumbersMonotonically() {
    WorkflowDefinitionDtos.View definition = createDefinition("numbering");
    WorkflowVersionDtos.View first =
        workflowVersionService.createDraft(
            new WorkflowVersionDtos.CreateDraft(definition.id(), null, null));

    jdbcTemplate.update(
        "UPDATE workflow_definitions SET active_draft_version_id = NULL WHERE id = ?",
        definition.id());
    jdbcTemplate.update(
        "UPDATE workflow_versions SET status = 'ARCHIVED' WHERE id = ?", first.id());

    WorkflowVersionDtos.View second =
        workflowVersionService.createDraft(
            new WorkflowVersionDtos.CreateDraft(definition.id(), null, null));

    assertThat(first.versionNo()).isEqualTo(1);
    assertThat(second.versionNo()).isEqualTo(2);
  }

  @Test
  @WithMockActor(roles = "USER")
  void rejectsDefinitionCatalogAndVersionMutationsForPlainUser() {
    UUID definitionId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                workflowDefinitionService.create(
                    new WorkflowDefinitionDtos.Create(
                        uniqueKey("forbidden-definition"),
                        "Forbidden definition",
                        null,
                        UUID.randomUUID())))
        .isInstanceOf(AuthorizationDeniedException.class);
    assertThatThrownBy(
            () ->
                workflowVersionService.createDraft(
                    new WorkflowVersionDtos.CreateDraft(definitionId, null, null)))
        .isInstanceOf(AuthorizationDeniedException.class);
    assertThatThrownBy(
            () ->
                requestTypeService.create(
                    new RequestTypeDtos.Create(
                        uniqueKey("forbidden-request"),
                        "Forbidden request",
                        null,
                        "GENERAL",
                        definitionId,
                        true,
                        objectMapper.createObjectNode())))
        .isInstanceOf(AuthorizationDeniedException.class);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void deletesOnlyMutableCatalogAndDraftRowsAndClearsTheDraftPointer() {
    WorkflowDefinitionDtos.View definition = createDefinition("delete-draft");
    WorkflowVersionDtos.View draft =
        workflowVersionService.createDraft(
            new WorkflowVersionDtos.CreateDraft(definition.id(), null, null));
    RequestTypeDtos.View requestType = createRequestType(definition.id(), "delete-request");

    requestTypeService.delete(requestType.id(), new ExpectedVersion(requestType.lockVersion()));
    workflowVersionService.deleteDraft(draft.id(), new ExpectedVersion(draft.lockVersion()));

    assertThat(requestTypeRepository.existsById(requestType.id())).isFalse();
    assertThat(workflowDefinitionService.get(definition.id()).activeDraftVersionId()).isNull();
    assertThatThrownBy(() -> workflowVersionService.get(draft.id()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  private WorkflowDefinitionDtos.View createDefinition(String prefix) {
    return workflowDefinitionService.create(
        new WorkflowDefinitionDtos.Create(
            uniqueKey(prefix), "Workflow " + prefix, "Definition fixture", UUID.randomUUID()));
  }

  private RequestTypeDtos.View createRequestType(UUID definitionId, String prefix) {
    return requestTypeService.create(
        new RequestTypeDtos.Create(
            uniqueKey(prefix),
            "Request " + prefix,
            null,
            "GENERAL",
            definitionId,
            true,
            objectMapper.createObjectNode()));
  }

  private static String uniqueKey(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
