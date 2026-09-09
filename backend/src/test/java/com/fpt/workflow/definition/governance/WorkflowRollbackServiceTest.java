package com.fpt.workflow.definition.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.publish.WorkflowPublishService;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowRollbackServiceTest {

  @Test
  void clonesKnownGoodVersionAndPublishesMonotonicNewVersion() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    UUID actorId = UUID.randomUUID();
    WorkflowDefinition definition =
        WorkflowDefinition.create(
            UUID.randomUUID(), "expenses", "Expenses", null, actorId, actorId, now);
    WorkflowVersion source =
        WorkflowVersion.createDraft(
            UUID.randomUUID(), definition.getId(), 3, null, null, actorId, now);
    source.publish(0, "known-good", JsonNodeFactory.instance.objectNode(), actorId, now);
    NodeDefinition node =
        NodeDefinition.create(
            UUID.randomUUID(),
            source.getId(),
            "start",
            "START",
            "Start",
            null,
            1,
            JsonNodeFactory.instance.objectNode(),
            null,
            null,
            JsonNodeFactory.instance.objectNode());

    WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    NodeDefinitionRepository nodes = mock(NodeDefinitionRepository.class);
    EdgeDefinitionRepository edges = mock(EdgeDefinitionRepository.class);
    WorkflowFormRepository forms = mock(WorkflowFormRepository.class);
    WorkflowVariableRepository variables = mock(WorkflowVariableRepository.class);
    WorkflowPublishService publisher = mock(WorkflowPublishService.class);
    AuditEventRepository audits = mock(AuditEventRepository.class);
    ActorContextProvider actors = mock(ActorContextProvider.class);
    UuidGenerator uuids = mock(UuidGenerator.class);
    PlatformClock clock = mock(PlatformClock.class);
    when(versions.findById(source.getId())).thenReturn(Optional.of(source));
    when(definitions.findByIdForUpdate(definition.getId())).thenReturn(Optional.of(definition));
    when(versions.findMaxVersionNoByDefinitionId(definition.getId())).thenReturn(7);
    when(nodes.findAllByWorkflowVersionIdOrderByNodeKeyAsc(source.getId()))
        .thenReturn(List.of(node));
    when(edges.findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(source.getId()))
        .thenReturn(List.of());
    when(forms.findAllByWorkflowVersionIdOrderByFormKeyAsc(source.getId())).thenReturn(List.of());
    when(variables.findAllByWorkflowVersionIdOrderByKeyAsc(source.getId())).thenReturn(List.of());
    when(uuids.generate()).thenAnswer(invocation -> UUID.randomUUID());
    when(clock.now()).thenReturn(now);
    when(actors.requireActor())
        .thenReturn(new ActorContext(actorId, "owner", Set.of(RoleKey.WORKFLOW_OWNER), Set.of()));
    when(versions.saveAndFlush(any(WorkflowVersion.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(publisher.publish(any(), any(), any(Long.class), any()))
        .thenReturn(
            new WorkflowPublishService.PublishResult(
                UUID.randomUUID(), 8, "rollback-checksum", WorkflowVersionStatus.PUBLISHED));

    WorkflowRollbackService service =
        new WorkflowRollbackService(
            definitions,
            versions,
            nodes,
            edges,
            forms,
            variables,
            publisher,
            audits,
            actors,
            uuids,
            clock);
    var result =
        service.rollback(source.getId(), new ExpectedVersion(0), new CommandId(UUID.randomUUID()));

    assertThat(result.sourceVersionId()).isEqualTo(source.getId());
    assertThat(result.versionNo()).isEqualTo(8);
    assertThat(definition.getActiveDraftVersionId()).isEqualTo(result.publishedVersionId());
    assertThat(source.getStatus()).isEqualTo(WorkflowVersionStatus.PUBLISHED);
    assertThat(source.getVersionNo()).isEqualTo(3);
    verify(nodes).saveAllAndFlush(any());
    verify(publisher).publish(any(), any(ExpectedVersion.class), any(Long.class), any());
  }

  @Test
  void rejectsRollbackOfNonPublishedVersion() {
    UUID actorId = UUID.randomUUID();
    WorkflowVersion draft =
        WorkflowVersion.createDraft(
            UUID.randomUUID(), UUID.randomUUID(), 1, null, null, actorId, Instant.now());
    WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    when(versions.findById(draft.getId())).thenReturn(Optional.of(draft));

    WorkflowRollbackService service =
        new WorkflowRollbackService(
            mock(WorkflowDefinitionRepository.class),
            versions,
            mock(NodeDefinitionRepository.class),
            mock(EdgeDefinitionRepository.class),
            mock(WorkflowFormRepository.class),
            mock(WorkflowVariableRepository.class),
            mock(WorkflowPublishService.class),
            mock(AuditEventRepository.class),
            mock(ActorContextProvider.class),
            mock(UuidGenerator.class),
            mock(PlatformClock.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.rollback(
                    draft.getId(), new ExpectedVersion(0), new CommandId(UUID.randomUUID())))
        .isInstanceOf(com.fpt.workflow.shared.api.CommandConflictException.class)
        .hasMessageContaining(
            "Rollback source must be a published or superseded immutable version");
  }

  @Test
  void rejectsRollbackWhenActiveDraftAlreadyExists() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    UUID actorId = UUID.randomUUID();
    WorkflowDefinition definition =
        WorkflowDefinition.create(
            UUID.randomUUID(), "expenses", "Expenses", null, actorId, actorId, now);
    definition.assignActiveDraft(UUID.randomUUID(), now);

    WorkflowVersion source =
        WorkflowVersion.createDraft(
            UUID.randomUUID(), definition.getId(), 1, null, null, actorId, now);
    source.publish(0, "ck", JsonNodeFactory.instance.objectNode(), actorId, now);

    WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    WorkflowVersionRepository versions = mock(WorkflowVersionRepository.class);
    when(versions.findById(source.getId())).thenReturn(Optional.of(source));
    when(definitions.findByIdForUpdate(definition.getId())).thenReturn(Optional.of(definition));

    WorkflowRollbackService service =
        new WorkflowRollbackService(
            definitions,
            versions,
            mock(NodeDefinitionRepository.class),
            mock(EdgeDefinitionRepository.class),
            mock(WorkflowFormRepository.class),
            mock(WorkflowVariableRepository.class),
            mock(WorkflowPublishService.class),
            mock(AuditEventRepository.class),
            mock(ActorContextProvider.class),
            mock(UuidGenerator.class),
            mock(PlatformClock.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.rollback(
                    source.getId(), new ExpectedVersion(0), new CommandId(UUID.randomUUID())))
        .isInstanceOf(com.fpt.workflow.shared.api.CommandConflictException.class)
        .hasMessageContaining("Rollback cannot replace an existing active draft");
  }
}
