package com.fpt.workflow.definition.governance;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.publish.WorkflowPublishService;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Rollback is forward-only: clone a known-good artifact and publish a new monotonic version. */
@Service
public class WorkflowRollbackService {

  private final WorkflowDefinitionRepository definitionRepository;
  private final WorkflowVersionRepository versionRepository;
  private final NodeDefinitionRepository nodeRepository;
  private final EdgeDefinitionRepository edgeRepository;
  private final WorkflowFormRepository formRepository;
  private final WorkflowVariableRepository variableRepository;
  private final WorkflowPublishService publishService;
  private final AuditEventRepository auditRepository;
  private final ActorContextProvider actorContextProvider;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public WorkflowRollbackService(
      WorkflowDefinitionRepository definitionRepository,
      WorkflowVersionRepository versionRepository,
      NodeDefinitionRepository nodeRepository,
      EdgeDefinitionRepository edgeRepository,
      WorkflowFormRepository formRepository,
      WorkflowVariableRepository variableRepository,
      WorkflowPublishService publishService,
      AuditEventRepository auditRepository,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.definitionRepository = definitionRepository;
    this.versionRepository = versionRepository;
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.formRepository = formRepository;
    this.variableRepository = variableRepository;
    this.publishService = publishService;
    this.auditRepository = auditRepository;
    this.actorContextProvider = actorContextProvider;
    this.uuids = uuids;
    this.clock = clock;
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public RollbackResult rollback(
      UUID sourceVersionId, ExpectedVersion expectedDefinitionVersion, CommandId commandId) {
    WorkflowVersion source = requireVersion(sourceVersionId);
    if (source.getStatus() != WorkflowVersionStatus.PUBLISHED
        && source.getStatus() != WorkflowVersionStatus.SUPERSEDED) {
      throw new CommandConflictException(
          "ROLLBACK_SOURCE_NOT_PUBLISHED",
          "Rollback source must be a published or superseded immutable version");
    }
    WorkflowDefinition definition =
        definitionRepository
            .findByIdForUpdate(source.getDefinitionId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "WORKFLOW_DEFINITION_NOT_FOUND", "WorkflowDefinition was not found"));
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(definition.getLockVersion()), expectedDefinitionVersion);
    if (definition.getActiveDraftVersionId() != null) {
      throw new CommandConflictException(
          "ACTIVE_DRAFT_EXISTS", "Rollback cannot replace an existing active draft");
    }

    ActorContext actor = actorContextProvider.requireActor();
    Instant now = clock.now();
    int nextVersionNo =
        Math.addExact(versionRepository.findMaxVersionNoByDefinitionId(definition.getId()), 1);
    UUID draftId = uuids.generate();
    WorkflowVersion draft =
        WorkflowVersion.createDraft(
            draftId,
            definition.getId(),
            nextVersionNo,
            sourceVersionId,
            sourceVersionId,
            actor.actorId(),
            now);
    versionRepository.saveAndFlush(draft);
    cloneContracts(sourceVersionId, draftId);
    definition.assignActiveDraft(draftId, now);
    definitionRepository.saveAndFlush(definition);
    recordRollbackAudit(sourceVersionId, draftId, nextVersionNo, commandId, actor, now);

    WorkflowPublishService.PublishResult published =
        publishService.publish(draftId, new ExpectedVersion(draft.getLockVersion()), 0, commandId);
    return new RollbackResult(
        sourceVersionId, draftId, published.versionNo(), published.checksum());
  }

  private void cloneContracts(UUID sourceVersionId, UUID draftId) {
    Map<UUID, UUID> nodeIds = new HashMap<>();
    var clonedNodes =
        nodeRepository.findAllByWorkflowVersionIdOrderByNodeKeyAsc(sourceVersionId).stream()
            .map(
                source -> {
                  UUID newId = uuids.generate();
                  nodeIds.put(source.getId(), newId);
                  return NodeDefinition.create(
                      newId,
                      draftId,
                      source.getNodeKey(),
                      source.getNodeType(),
                      source.getName(),
                      source.getDescription(),
                      source.getConfigSchemaVersion(),
                      source.getConfigJson(),
                      source.getInputSchemaJson(),
                      source.getOutputSchemaJson(),
                      source.getPositionJson());
                })
            .toList();
    nodeRepository.saveAllAndFlush(clonedNodes);

    var clonedEdges =
        edgeRepository.findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(sourceVersionId).stream()
            .map(
                source ->
                    EdgeDefinition.create(
                        uuids.generate(),
                        draftId,
                        requiredClone(nodeIds, source.getSourceNodeId()),
                        source.getSourcePort(),
                        requiredClone(nodeIds, source.getTargetNodeId()),
                        source.getConditionJson(),
                        source.getPriority(),
                        source.isDefaultTransition(),
                        source.getTransitionType(),
                        source.getLabel(),
                        source.getConfigJson()))
            .toList();
    edgeRepository.saveAllAndFlush(clonedEdges);

    formRepository.saveAllAndFlush(
        formRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(sourceVersionId).stream()
            .map(
                source ->
                    WorkflowForm.create(
                        uuids.generate(),
                        draftId,
                        source.getFormKey(),
                        source.getFormType(),
                        source.getSchemaJson(),
                        source.getSchemaChecksum()))
            .toList());
    variableRepository.saveAllAndFlush(
        variableRepository.findAllByWorkflowVersionIdOrderByKeyAsc(sourceVersionId).stream()
            .map(
                source ->
                    WorkflowVariable.create(
                        uuids.generate(),
                        draftId,
                        source.getKey(),
                        source.getType(),
                        source.getScope(),
                        source.getDefaultJson(),
                        source.isMutable(),
                        source.isSensitive()))
            .toList());
  }

  private UUID requiredClone(Map<UUID, UUID> nodeIds, UUID sourceId) {
    UUID cloneId = nodeIds.get(sourceId);
    if (cloneId == null) {
      throw new IllegalStateException("Published edge references a node outside its version");
    }
    return cloneId;
  }

  private WorkflowVersion requireVersion(UUID id) {
    return versionRepository
        .findById(id)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_VERSION_NOT_FOUND", "WorkflowVersion was not found"));
  }

  private void recordRollbackAudit(
      UUID sourceVersionId,
      UUID draftId,
      int versionNo,
      CommandId commandId,
      ActorContext actor,
      Instant now) {
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("sourceVersionId", sourceVersionId.toString());
    metadata.put("newVersionNo", versionNo);
    auditRepository.save(
        AuditEvent.record(
            uuids.generate(),
            "WORKFLOW_VERSION",
            draftId,
            "WORKFLOW_VERSION_ROLLBACK_CREATED",
            actor.actorId(),
            actor.actorId(),
            correlationId(),
            commandId,
            metadata,
            now));
  }

  private CorrelationId correlationId() {
    String value = MDC.get("correlationId");
    if (value != null) {
      try {
        return CorrelationId.parse(value);
      } catch (IllegalArgumentException ignored) {
        // Generate a trusted server-side correlation id below.
      }
    }
    return CorrelationId.generate(uuids);
  }

  public record RollbackResult(
      UUID sourceVersionId, UUID publishedVersionId, int versionNo, String checksum) {}
}
