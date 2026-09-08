package com.fpt.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.dto.WorkflowDefinitionDtos;
import com.fpt.workflow.definition.dto.WorkflowGraphDtos;
import com.fpt.workflow.definition.dto.WorkflowVersionDtos;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.service.WorkflowDefinitionService;
import com.fpt.workflow.definition.service.WorkflowGraphService;
import com.fpt.workflow.definition.service.WorkflowVersionService;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkflowGraphPersistenceIT {

  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_graph_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowDefinitionService workflowDefinitionService;
  @Autowired private WorkflowVersionService workflowVersionService;
  @Autowired private WorkflowGraphService workflowGraphService;
  @Autowired private NodeDefinitionRepository nodeDefinitionRepository;
  @Autowired private EdgeDefinitionRepository edgeDefinitionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void migratesPersistsAndQueriesGraphWhileRejectingDuplicateNodeKey() {
    DraftFixture fixture = createDraft("graph");
    Integer appliedMigration =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success",
            Integer.class);

    WorkflowGraphDtos.NodeMutation source =
        createNode(fixture.draft(), "start", "START", fixture.draftState());
    WorkflowGraphDtos.NodeMutation target =
        createNode(fixture.draft(), "approval", "APPROVAL", source.draft());
    WorkflowGraphDtos.EdgeMutation edge =
        createEdge(fixture.draft(), source.node().id(), target.node().id(), target.draft());

    assertThat(appliedMigration).isEqualTo(1);
    assertThat(workflowGraphService.listNodes(fixture.draft().id()))
        .extracting(WorkflowGraphDtos.NodeView::nodeKey)
        .containsExactly("approval", "start");
    assertThat(workflowGraphService.listEdges(fixture.draft().id()))
        .singleElement()
        .extracting(WorkflowGraphDtos.EdgeView::id)
        .isEqualTo(edge.edge().id());
    assertThat(edge.draft().revision()).isEqualTo(3);
    assertThat(edge.draft().lockVersion()).isEqualTo(3);

    assertThatThrownBy(() -> createNode(fixture.draft(), "start", "END", edge.draft()))
        .isInstanceOf(CommandConflictException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void rejectsCrossVersionEdgeInServiceAndCompositeForeignKeys() {
    DraftFixture first = createDraft("cross-a");
    DraftFixture second = createDraft("cross-b");
    WorkflowGraphDtos.NodeMutation firstNode =
        createNode(first.draft(), "source", "START", first.draftState());
    WorkflowGraphDtos.NodeMutation secondNode =
        createNode(second.draft(), "target", "END", second.draftState());

    assertThatThrownBy(
            () ->
                createEdge(
                    first.draft(),
                    firstNode.node().id(),
                    secondNode.node().id(),
                    firstNode.draft()))
        .isInstanceOf(CommandConflictException.class)
        .hasMessageContaining("edge WorkflowVersion");

    assertThatThrownBy(
            () -> insertEdge(first.draft().id(), firstNode.node().id(), secondNode.node().id()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void rejectsInvalidVersionAndNodeForeignKeys() {
    DraftFixture fixture = createDraft("invalid-fk");
    WorkflowGraphDtos.NodeMutation source =
        createNode(fixture.draft(), "source", "START", fixture.draftState());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO workflow_nodes "
                        + "(id, workflow_version_id, node_key, node_type, name, "
                        + "config_schema_version, config_json, position_json) "
                        + "VALUES (?, ?, 'orphan', 'START', 'Orphan', 1, '{}'::jsonb, '{}'::jsonb)",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () -> insertEdge(fixture.draft().id(), source.node().id(), UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void blocksPublishedGraphMutationsAtServiceAndDatabaseLayers() {
    DraftFixture fixture = createDraft("published");
    WorkflowGraphDtos.NodeMutation source =
        createNode(fixture.draft(), "source", "START", fixture.draftState());
    WorkflowGraphDtos.NodeMutation target =
        createNode(fixture.draft(), "target", "END", source.draft());
    WorkflowGraphDtos.EdgeMutation edge =
        createEdge(fixture.draft(), source.node().id(), target.node().id(), target.draft());

    jdbcTemplate.update(
        "UPDATE workflow_definitions SET active_draft_version_id = NULL WHERE id = ?",
        fixture.definitionId());
    jdbcTemplate.update(
        "UPDATE workflow_versions "
            + "SET status = 'PUBLISHED', checksum = 'sha256-published', "
            + "execution_package_json = '{}'::jsonb, published_by = ?, published_at = now(), "
            + "lock_version = lock_version + 1 WHERE id = ?",
        ACTOR_ID,
        fixture.draft().id());
    WorkflowVersionDtos.View published = workflowVersionService.get(fixture.draft().id());

    assertThatThrownBy(
            () ->
                workflowGraphService.updateNode(
                    source.node().id(),
                    updateNode("Renamed"),
                    new ExpectedVersion(published.lockVersion()),
                    published.revision()))
        .isInstanceOf(CommandConflictException.class)
        .hasMessageContaining("DRAFT");
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE workflow_nodes SET name = 'Database mutation' WHERE id = ?",
                    source.node().id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM workflow_edges WHERE id = ?", edge.edge().id()))
        .isInstanceOf(DataAccessException.class);
    assertThat(nodeDefinitionRepository.existsById(source.node().id())).isTrue();
    assertThat(edgeDefinitionRepository.existsById(edge.edge().id())).isTrue();
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void cascadesNormalizedGraphWhenDeletingItsDraftVersion() {
    DraftFixture fixture = createDraft("cascade");
    WorkflowGraphDtos.NodeMutation source =
        createNode(fixture.draft(), "source", "START", fixture.draftState());
    WorkflowGraphDtos.NodeMutation target =
        createNode(fixture.draft(), "target", "END", source.draft());
    WorkflowGraphDtos.EdgeMutation edge =
        createEdge(fixture.draft(), source.node().id(), target.node().id(), target.draft());

    workflowVersionService.deleteDraft(
        fixture.draft().id(), new ExpectedVersion(edge.draft().lockVersion()));

    assertThat(nodeDefinitionRepository.existsById(source.node().id())).isFalse();
    assertThat(nodeDefinitionRepository.existsById(target.node().id())).isFalse();
    assertThat(edgeDefinitionRepository.existsById(edge.edge().id())).isFalse();
  }

  @Test
  @WithMockActor(roles = "USER")
  void forbidsGraphMutationWithoutEditorPermission() {
    WorkflowGraphDtos.CreateNode request =
        new WorkflowGraphDtos.CreateNode(
            UUID.randomUUID(),
            "source",
            "START",
            "Forbidden source",
            null,
            1,
            objectMapper.createObjectNode(),
            null,
            null,
            objectMapper.createObjectNode());

    assertThatThrownBy(() -> workflowGraphService.createNode(request, new ExpectedVersion(0), 0))
        .isInstanceOf(AuthorizationDeniedException.class);
  }

  @Test
  @WithMockActor(roles = "ADMIN")
  void allowsAdminToMutateDraftGraph() {
    DraftFixture fixture = createDraft("admin");

    WorkflowGraphDtos.NodeMutation created =
        createNode(fixture.draft(), "source", "START", fixture.draftState());

    assertThat(created.node().nodeKey()).isEqualTo("source");
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_EDITOR")
  void allowsEditorToMutateAnExistingDraftGraph() {
    DraftFixture fixture = createDraftDirectly("editor");

    WorkflowGraphDtos.NodeMutation created =
        createNode(fixture.draft(), "source", "START", fixture.draftState());

    assertThat(created.node().nodeKey()).isEqualTo("source");
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void blocksUnknownExecutableConfigAtTheGraphWriteBoundary() {
    DraftFixture fixture = createDraft("strict-manifest");
    WorkflowGraphDtos.CreateNode request =
        new WorkflowGraphDtos.CreateNode(
            fixture.draft().id(),
            "start",
            "START",
            "Start",
            null,
            1,
            objectMapper.createObjectNode().put("unknownExecutableProperty", true),
            null,
            null,
            objectMapper.createObjectNode());

    assertThatThrownBy(
            () ->
                workflowGraphService.createNode(
                    request,
                    new ExpectedVersion(fixture.draftState().lockVersion()),
                    fixture.draftState().revision()))
        .isInstanceOf(com.fpt.workflow.shared.api.UnprocessableCommandException.class)
        .hasMessageContaining("SCHEMA.UNKNOWN_PROPERTY");
  }

  private DraftFixture createDraft(String prefix) {
    WorkflowDefinitionDtos.View definition =
        workflowDefinitionService.create(
            new WorkflowDefinitionDtos.Create(
                uniqueKey(prefix), "Workflow " + prefix, null, UUID.randomUUID()));
    WorkflowVersionDtos.View draft =
        workflowVersionService.createDraft(
            new WorkflowVersionDtos.CreateDraft(definition.id(), null, null));
    return new DraftFixture(
        definition.id(),
        draft,
        new WorkflowGraphDtos.DraftState(draft.id(), draft.revision(), draft.lockVersion()));
  }

  private DraftFixture createDraftDirectly(String prefix) {
    UUID definitionId = UUID.randomUUID();
    UUID draftId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO workflow_definitions "
            + "(id, key, name, lifecycle, owner_id, created_by, created_at, updated_at, lock_version) "
            + "VALUES (?, ?, ?, 'ACTIVE', ?, ?, now(), now(), 0)",
        definitionId,
        uniqueKey(prefix),
        "Workflow " + prefix,
        UUID.randomUUID(),
        ACTOR_ID);
    jdbcTemplate.update(
        "INSERT INTO workflow_versions "
            + "(id, definition_id, version_no, status, revision, created_by, created_at, lock_version) "
            + "VALUES (?, ?, 1, 'DRAFT', 0, ?, now(), 0)",
        draftId,
        definitionId,
        ACTOR_ID);
    jdbcTemplate.update(
        "UPDATE workflow_definitions SET active_draft_version_id = ? WHERE id = ?",
        draftId,
        definitionId);
    WorkflowVersionDtos.View draft = workflowVersionService.get(draftId);
    return new DraftFixture(
        definitionId,
        draft,
        new WorkflowGraphDtos.DraftState(draft.id(), draft.revision(), draft.lockVersion()));
  }

  private WorkflowGraphDtos.NodeMutation createNode(
      WorkflowVersionDtos.View draft,
      String nodeKey,
      String nodeType,
      WorkflowGraphDtos.DraftState state) {
    return workflowGraphService.createNode(
        new WorkflowGraphDtos.CreateNode(
            draft.id(),
            nodeKey,
            nodeType,
            "Node " + nodeKey,
            null,
            1,
            nodeConfig(nodeType),
            null,
            null,
            objectMapper.createObjectNode().put("x", 10).put("y", 20)),
        new ExpectedVersion(state.lockVersion()),
        state.revision());
  }

  private WorkflowGraphDtos.EdgeMutation createEdge(
      WorkflowVersionDtos.View draft,
      UUID sourceNodeId,
      UUID targetNodeId,
      WorkflowGraphDtos.DraftState state) {
    return workflowGraphService.createEdge(
        new WorkflowGraphDtos.CreateEdge(
            draft.id(),
            sourceNodeId,
            "STARTED",
            targetNodeId,
            null,
            0,
            true,
            TransitionType.NORMAL,
            "Continue",
            objectMapper.createObjectNode()),
        new ExpectedVersion(state.lockVersion()),
        state.revision());
  }

  private WorkflowGraphDtos.UpdateNode updateNode(String name) {
    return new WorkflowGraphDtos.UpdateNode(
        "START",
        name,
        null,
        1,
        objectMapper.createObjectNode(),
        null,
        null,
        objectMapper.createObjectNode());
  }

  private com.fasterxml.jackson.databind.node.ObjectNode nodeConfig(String nodeType) {
    var config = objectMapper.createObjectNode();
    if (nodeType.equals("APPROVAL") || nodeType.equals("REVIEW")) {
      config.putObject("participant").put("resolver", "CREATOR_MANAGER");
      config.putArray("allowedActions").add("APPROVE").add("REJECT");
    }
    return config;
  }

  private void insertEdge(UUID versionId, UUID sourceNodeId, UUID targetNodeId) {
    jdbcTemplate.update(
        "INSERT INTO workflow_edges "
            + "(id, workflow_version_id, source_node_id, source_port, target_node_id, priority, "
            + "is_default, transition_type, config_json) "
            + "VALUES (?, ?, ?, 'SUCCESS', ?, 0, false, 'NORMAL', '{}'::jsonb)",
        UUID.randomUUID(),
        versionId,
        sourceNodeId,
        targetNodeId);
  }

  private static String uniqueKey(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private record DraftFixture(
      UUID definitionId, WorkflowVersionDtos.View draft, WorkflowGraphDtos.DraftState draftState) {}
}
