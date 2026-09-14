package com.fpt.workflow.definition.api;
import com.fpt.workflow.definition.domain.WorkflowInputDefinition;
import com.fpt.workflow.definition.domain.WorkflowStateDefinition;
import com.fpt.workflow.definition.service.WorkflowContractService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/workflows/{workflowId}/versions/{versionId}")
@PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
public class WorkflowContractController {
  private final WorkflowContractService service;
  public WorkflowContractController(WorkflowContractService service){this.service=service;}
  @GetMapping("/inputs") public List<WorkflowInputDefinition> inputs(@PathVariable UUID workflowId,@PathVariable UUID versionId){return service.inputs(workflowId,versionId);}
  @PutMapping("/inputs") public List<WorkflowInputDefinition> replaceInputs(@PathVariable UUID workflowId,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision,@RequestBody List<WorkflowContractService.InputCommand> commands){return service.replaceInputs(workflowId,versionId,expectedRevision,commands);}
  @GetMapping("/states") public List<WorkflowStateDefinition> states(@PathVariable UUID workflowId,@PathVariable UUID versionId){return service.states(workflowId,versionId);}
  @PutMapping("/states") public List<WorkflowStateDefinition> replaceStates(@PathVariable UUID workflowId,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision,@RequestBody List<WorkflowContractService.StateCommand> commands){return service.replaceStates(workflowId,versionId,expectedRevision,commands);}
}
