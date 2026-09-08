package com.fpt.workflow.definition.validation;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.ValidationSeverity;
import com.fpt.workflow.definition.domain.WorkflowValidationIssue;
import com.fpt.workflow.definition.domain.WorkflowValidationRun;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowValidationIssueRepository;
import com.fpt.workflow.definition.repository.WorkflowValidationRunRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.time.PlatformClock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowValidationService {

  private final WorkflowVersionRepository versionRepository;
  private final NodeDefinitionRepository nodeRepository;
  private final EdgeDefinitionRepository edgeRepository;
  private final WorkflowFormRepository formRepository;
  private final WorkflowVariableRepository variableRepository;
  private final WorkflowValidationRunRepository runRepository;
  private final WorkflowValidationIssueRepository issueRepository;
  private final WorkflowValidationCompiler compiler;
  private final ActorContextProvider actorContextProvider;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;

  public WorkflowValidationService(
      WorkflowVersionRepository versionRepository,
      NodeDefinitionRepository nodeRepository,
      EdgeDefinitionRepository edgeRepository,
      WorkflowFormRepository formRepository,
      WorkflowVariableRepository variableRepository,
      WorkflowValidationRunRepository runRepository,
      WorkflowValidationIssueRepository issueRepository,
      WorkflowValidationCompiler compiler,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock) {
    this.versionRepository = versionRepository;
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.formRepository = formRepository;
    this.variableRepository = variableRepository;
    this.runRepository = runRepository;
    this.issueRepository = issueRepository;
    this.compiler = compiler;
    this.actorContextProvider = actorContextProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
  }

  @Transactional
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public PersistedValidation validate(UUID workflowVersionId) {
    ValidationCompilation compilation = compileCurrent(workflowVersionId);
    UUID runId = uuidGenerator.generate();
    int errors = count(compilation, ValidationSeverity.ERROR);
    int warnings =
        count(compilation, ValidationSeverity.WARNING)
            + count(compilation, ValidationSeverity.ACK_REQUIRED_WARNING);
    int info = count(compilation, ValidationSeverity.INFO);
    WorkflowValidationRun run =
        runRepository.save(
            WorkflowValidationRun.create(
                runId,
                compilation.workflowVersionId(),
                compilation.revision(),
                compilation.definitionChecksum(),
                compilation.valid(),
                compilation.publishable(),
                errors,
                warnings,
                info,
                actorContextProvider.requireActor().actorId(),
                clock.now()));
    List<WorkflowValidationIssue> persisted =
        compilation.issues().stream()
            .map(
                issue ->
                    WorkflowValidationIssue.create(
                        uuidGenerator.generate(),
                        runId,
                        issue.code(),
                        issue.severity(),
                        issue.resourceType(),
                        issue.resourceId(),
                        issue.fieldPath(),
                        issue.message(),
                        issue.suggestion(),
                        issue.metadata() == null
                            ? JsonNodeFactory.instance.objectNode()
                            : issue.metadata()))
            .map(issueRepository::save)
            .toList();
    return new PersistedValidation(run, persisted, compilation);
  }

  @Transactional(readOnly = true)
  public ValidationCompilation compileCurrent(UUID workflowVersionId) {
    return compiler.compile(loadCurrent(workflowVersionId));
  }

  @Transactional(readOnly = true)
  public ValidationDefinition loadCurrent(UUID workflowVersionId) {
    WorkflowVersion version =
        versionRepository
            .findById(workflowVersionId)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "WORKFLOW_VERSION_NOT_FOUND", "WorkflowVersion was not found"));
    return new ValidationDefinition(
        version,
        nodeRepository.findAllByWorkflowVersionIdOrderByNodeKeyAsc(workflowVersionId),
        edgeRepository.findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(workflowVersionId),
        formRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(workflowVersionId),
        variableRepository.findAllByWorkflowVersionIdOrderByKeyAsc(workflowVersionId));
  }

  private int count(ValidationCompilation compilation, ValidationSeverity severity) {
    return Math.toIntExact(
        compilation.issues().stream().filter(issue -> issue.severity() == severity).count());
  }

  public record PersistedValidation(
      WorkflowValidationRun run,
      List<WorkflowValidationIssue> issues,
      ValidationCompilation compilation) {}
}
