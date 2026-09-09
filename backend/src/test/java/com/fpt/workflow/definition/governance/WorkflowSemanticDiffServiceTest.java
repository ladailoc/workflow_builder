package com.fpt.workflow.definition.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.validation.ValidationDefinition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowSemanticDiffServiceTest {

  @Test
  void reportsStableNodeParticipantSlaAndConnectorChangesWithoutDatabaseIds() {
    UUID definitionId = UUID.randomUUID();
    WorkflowVersion fromVersion = version(definitionId, 1);
    WorkflowVersion toVersion = version(definitionId, 2);
    NodeDefinition before =
        node(
            fromVersion.getId(),
            "{\"participant\":{\"type\":\"MANAGER\"},\"sla\":{\"hours\":8},"
                + "\"connectorActionVersionId\":\"v1\"}");
    NodeDefinition after =
        node(
            toVersion.getId(),
            "{\"participant\":{\"type\":\"OWNER\"},\"sla\":{\"hours\":4},"
                + "\"connectorActionVersionId\":\"v2\"}");
    WorkflowSemanticDiffService service =
        new WorkflowSemanticDiffService(
            mock(com.fpt.workflow.definition.validation.WorkflowValidationService.class),
            new ObjectMapper());

    var result =
        service.compare(
            new ValidationDefinition(fromVersion, List.of(before), List.of(), List.of(), List.of()),
            new ValidationDefinition(toVersion, List.of(after), List.of(), List.of(), List.of()));

    assertThat(result.nodes())
        .singleElement()
        .extracting(change -> change.resourceKey())
        .isEqualTo("approval");
    assertThat(result.participantPolicies()).hasSize(1);
    assertThat(result.slaPolicies()).hasSize(1);
    assertThat(result.connectorActionVersions()).hasSize(1);
  }

  @Test
  void reportsAddedAndRemovedNodes() {
    UUID definitionId = UUID.randomUUID();
    WorkflowVersion fromVersion = version(definitionId, 1);
    WorkflowVersion toVersion = version(definitionId, 2);
    NodeDefinition existing = node(fromVersion.getId(), "nodeA", "{}");
    NodeDefinition removed = node(fromVersion.getId(), "nodeB", "{}");
    NodeDefinition added = node(toVersion.getId(), "nodeC", "{}");

    WorkflowSemanticDiffService service =
        new WorkflowSemanticDiffService(
            mock(com.fpt.workflow.definition.validation.WorkflowValidationService.class),
            new ObjectMapper());

    var result =
        service.compare(
            new ValidationDefinition(
                fromVersion, List.of(existing, removed), List.of(), List.of(), List.of()),
            new ValidationDefinition(
                toVersion,
                List.of(node(toVersion.getId(), "nodeA", "{}"), added),
                List.of(),
                List.of(),
                List.of()));

    assertThat(result.nodes()).hasSize(2);
    assertThat(result.nodes())
        .filteredOn(c -> c.changeType() == WorkflowSemanticDiffService.ChangeType.REMOVED)
        .extracting(WorkflowSemanticDiffService.SemanticChange::resourceKey)
        .containsExactly("nodeB");
    assertThat(result.nodes())
        .filteredOn(c -> c.changeType() == WorkflowSemanticDiffService.ChangeType.ADDED)
        .extracting(WorkflowSemanticDiffService.SemanticChange::resourceKey)
        .containsExactly("nodeC");
  }

  @Test
  void reportsEdgeChangesAndFormChanges() throws Exception {
    UUID definitionId = UUID.randomUUID();
    WorkflowVersion fromVersion = version(definitionId, 1);
    WorkflowVersion toVersion = version(definitionId, 2);
    NodeDefinition start1 = node(fromVersion.getId(), "start", "{}");
    NodeDefinition end1 = node(fromVersion.getId(), "end", "{}");
    NodeDefinition start2 = node(toVersion.getId(), "start", "{}");
    NodeDefinition end2 = node(toVersion.getId(), "end", "{}");

    var edgeBefore =
        com.fpt.workflow.definition.domain.EdgeDefinition.create(
            UUID.randomUUID(),
            fromVersion.getId(),
            start1.getId(),
            "OUT",
            end1.getId(),
            null,
            0,
            false,
            com.fpt.workflow.definition.domain.TransitionType.NORMAL,
            "Old Label",
            JsonNodeFactory.instance.objectNode());

    var edgeAfter =
        com.fpt.workflow.definition.domain.EdgeDefinition.create(
            UUID.randomUUID(),
            toVersion.getId(),
            start2.getId(),
            "OUT",
            end2.getId(),
            null,
            0,
            false,
            com.fpt.workflow.definition.domain.TransitionType.NORMAL,
            "New Label",
            JsonNodeFactory.instance.objectNode());

    var formBefore =
        com.fpt.workflow.form.domain.WorkflowForm.create(
            UUID.randomUUID(),
            fromVersion.getId(),
            "request_form",
            com.fpt.workflow.form.domain.WorkflowFormType.TICKET_FORM,
            new ObjectMapper().readTree("{\"title\":\"Old Form\"}"),
            "checksum1");

    var formAfter =
        com.fpt.workflow.form.domain.WorkflowForm.create(
            UUID.randomUUID(),
            toVersion.getId(),
            "request_form",
            com.fpt.workflow.form.domain.WorkflowFormType.TICKET_FORM,
            new ObjectMapper().readTree("{\"title\":\"New Form\"}"),
            "checksum2");

    WorkflowSemanticDiffService service =
        new WorkflowSemanticDiffService(
            mock(com.fpt.workflow.definition.validation.WorkflowValidationService.class),
            new ObjectMapper());

    var result =
        service.compare(
            new ValidationDefinition(
                fromVersion,
                List.of(start1, end1),
                List.of(edgeBefore),
                List.of(formBefore),
                List.of()),
            new ValidationDefinition(
                toVersion,
                List.of(start2, end2),
                List.of(edgeAfter),
                List.of(formAfter),
                List.of()));

    assertThat(result.edges())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.changeType())
                  .isEqualTo(WorkflowSemanticDiffService.ChangeType.MODIFIED);
              assertThat(change.resourceKey()).isEqualTo("start:OUT->end#0");
            });

    assertThat(result.forms())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.changeType())
                  .isEqualTo(WorkflowSemanticDiffService.ChangeType.MODIFIED);
              assertThat(change.resourceKey()).isEqualTo("request_form");
            });
  }

  @Test
  void rejectsDiffBetweenDifferentWorkflowDefinitions() {
    UUID def1 = UUID.randomUUID();
    UUID def2 = UUID.randomUUID();
    WorkflowVersion v1 = version(def1, 1);
    WorkflowVersion v2 = version(def2, 1);

    var validationMock =
        mock(com.fpt.workflow.definition.validation.WorkflowValidationService.class);
    when(validationMock.loadCurrent(v1.getId()))
        .thenReturn(new ValidationDefinition(v1, List.of(), List.of(), List.of(), List.of()));
    when(validationMock.loadCurrent(v2.getId()))
        .thenReturn(new ValidationDefinition(v2, List.of(), List.of(), List.of(), List.of()));

    WorkflowSemanticDiffService service =
        new WorkflowSemanticDiffService(validationMock, new ObjectMapper());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.diff(v1.getId(), v2.getId()))
        .isInstanceOf(com.fpt.workflow.shared.api.CommandConflictException.class)
        .hasMessageContaining("Semantic diff requires versions of the same WorkflowDefinition");
  }

  private WorkflowVersion version(UUID definitionId, int versionNo) {
    return WorkflowVersion.createDraft(
        UUID.randomUUID(),
        definitionId,
        versionNo,
        null,
        null,
        UUID.randomUUID(),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private NodeDefinition node(UUID versionId, String key, String config) {
    try {
      return NodeDefinition.create(
          UUID.randomUUID(),
          versionId,
          key,
          "APPROVAL",
          key,
          null,
          1,
          new ObjectMapper().readTree(config),
          null,
          null,
          JsonNodeFactory.instance.objectNode());
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private NodeDefinition node(UUID versionId, String config) {
    return node(versionId, "approval", config);
  }
}
