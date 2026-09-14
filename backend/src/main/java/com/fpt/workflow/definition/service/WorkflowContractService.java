package com.fpt.workflow.definition.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.WorkflowInputDefinition;
import com.fpt.workflow.definition.domain.WorkflowStateDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.WorkflowInputDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowStateDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowContractService {
  private final WorkflowVersionRepository versions;
  private final WorkflowInputDefinitionRepository inputs;
  private final WorkflowStateDefinitionRepository states;
  private final UuidGenerator uuids;

  public WorkflowContractService(WorkflowVersionRepository versions,
      WorkflowInputDefinitionRepository inputs, WorkflowStateDefinitionRepository states,
      UuidGenerator uuids) {
    this.versions = versions; this.inputs = inputs; this.states = states; this.uuids = uuids;
  }

  @Transactional
  public List<WorkflowInputDefinition> replaceInputs(UUID workflowId, UUID versionId,
      long expectedRevision, List<InputCommand> commands) {
    WorkflowVersion version = mutableVersion(workflowId, versionId);
    Set<String> keys = new HashSet<>();
    for (InputCommand command : commands) {
      String key = command.inputKey().trim().toLowerCase(Locale.ROOT);
      if (!keys.add(key)) throw new IllegalArgumentException("WORKFLOW-INPUT-DUPLICATE: " + key);
      if (command.defaultJson() != null) CanonicalValueValidator.requireValid(command.type(), command.defaultJson());
    }
    inputs.deleteAllByWorkflowVersionId(versionId); inputs.flush();
    List<WorkflowInputDefinition> saved = inputs.saveAll(commands.stream().map(command ->
        WorkflowInputDefinition.create(uuids.generate(), versionId, command.inputKey(),
            command.semanticTag(), command.type(), command.required(), command.defaultJson(),
            command.schemaJson(), command.sensitive(), command.description(), command.ordinal())).toList());
    version.recordGraphMutation(expectedRevision);
    return List.copyOf(saved);
  }

  @Transactional
  public List<WorkflowStateDefinition> replaceStates(UUID workflowId, UUID versionId,
      long expectedRevision, List<StateCommand> commands) {
    WorkflowVersion version = mutableVersion(workflowId, versionId);
    Set<String> keys = new HashSet<>();
    for (StateCommand command : commands) {
      String key = command.stateKey().trim().toUpperCase(Locale.ROOT);
      if (!keys.add(key)) throw new IllegalArgumentException("WORKFLOW-STATE-DUPLICATE: " + key);
    }
    states.deleteAllByWorkflowVersionId(versionId); states.flush();
    List<WorkflowStateDefinition> saved = states.saveAll(commands.stream().map(command ->
        WorkflowStateDefinition.create(uuids.generate(), versionId, command.stateKey(), command.name(),
            command.description(), command.stateGroup(), command.terminal(), command.displayOrder(),
            command.metadataJson())).toList());
    version.recordGraphMutation(expectedRevision);
    return List.copyOf(saved);
  }

  @Transactional(readOnly = true)
  public List<WorkflowInputDefinition> inputs(UUID workflowId, UUID versionId) {
    requireOwned(workflowId, versionId); return inputs.findAllByWorkflowVersionIdOrderByOrdinalAsc(versionId);
  }
  @Transactional(readOnly = true)
  public List<WorkflowStateDefinition> states(UUID workflowId, UUID versionId) {
    requireOwned(workflowId, versionId); return states.findAllByWorkflowVersionIdOrderByDisplayOrderAsc(versionId);
  }
  private WorkflowVersion mutableVersion(UUID workflowId, UUID versionId) {
    WorkflowVersion version = requireOwned(workflowId, versionId); version.requireDraft(); return version;
  }
  private WorkflowVersion requireOwned(UUID workflowId, UUID versionId) {
    WorkflowVersion version = versions.findById(versionId).orElseThrow(() -> new IllegalArgumentException("WorkflowVersion not found: " + versionId));
    if (!version.getDefinitionId().equals(workflowId)) throw new IllegalArgumentException("WorkflowVersion does not belong to WorkflowDefinition");
    return version;
  }
  public record InputCommand(String inputKey, String semanticTag, TypeDescriptor type, boolean required,
      JsonNode defaultJson, JsonNode schemaJson, boolean sensitive, String description, int ordinal) {}
  public record StateCommand(String stateKey, String name, String description, String stateGroup,
      boolean terminal, int displayOrder, JsonNode metadataJson) {}
}
