package com.fpt.workflow.definition.governance;

import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflow-versions")
public class WorkflowGovernanceController {

  private final WorkflowSemanticDiffService diffService;
  private final WorkflowRollbackService rollbackService;

  public WorkflowGovernanceController(
      WorkflowSemanticDiffService diffService, WorkflowRollbackService rollbackService) {
    this.diffService = diffService;
    this.rollbackService = rollbackService;
  }

  @GetMapping("/{fromVersionId}/diff/{toVersionId}")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'OPERATOR', 'ADMIN')")
  public WorkflowSemanticDiffService.SemanticDiff diff(
      @PathVariable UUID fromVersionId, @PathVariable UUID toVersionId) {
    return diffService.diff(fromVersionId, toVersionId);
  }

  @PostMapping("/{sourceVersionId}/rollback")
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'ADMIN')")
  public WorkflowRollbackService.RollbackResult rollback(
      @PathVariable UUID sourceVersionId,
      @RequestHeader("If-Match") long expectedDefinitionVersion,
      @Valid @RequestBody RollbackCommand command) {
    return rollbackService.rollback(
        sourceVersionId, new ExpectedVersion(expectedDefinitionVersion), command.commandId());
  }

  public record RollbackCommand(@NotNull CommandId commandId) {}
}
