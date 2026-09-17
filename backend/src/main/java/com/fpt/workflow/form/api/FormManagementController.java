package com.fpt.workflow.form.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.form.domain.FormVersion;
import com.fpt.workflow.form.engine.FormValidationResult;
import com.fpt.workflow.form.service.FormManagementService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/forms") @PreAuthorize("hasAnyRole('WORKFLOW_OWNER','WORKFLOW_EDITOR','ADMIN')")
public class FormManagementController {
  private final FormManagementService service;public FormManagementController(FormManagementService service){this.service=service;}
  @GetMapping public List<FormManagementService.FormCatalogItem> catalog(){return service.publishedCatalog();}
  @PostMapping @ResponseStatus(HttpStatus.CREATED) public FormManagementService.FormView create(@RequestBody FormManagementService.CreateForm command){return service.create(command);}
  @PostMapping("/{id}/draft") @ResponseStatus(HttpStatus.CREATED) public FormVersion draft(@PathVariable UUID id){return service.createDraft(id);}
  @PutMapping("/{id}/versions/{versionId}") public FormVersion update(@PathVariable UUID id,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision,@RequestBody JsonNode schema){return service.update(id,versionId,expectedRevision,schema);}
  @PostMapping("/{id}/versions/{versionId}/validate") public FormValidationResult validate(@PathVariable UUID id,@PathVariable UUID versionId){return service.validate(id,versionId);}
  @PostMapping("/{id}/versions/{versionId}/publish") public FormVersion publish(@PathVariable UUID id,@PathVariable UUID versionId,@RequestHeader("If-Match") long expectedRevision){return service.publish(id,versionId,expectedRevision);}
  @GetMapping("/{id}/versions") public List<FormVersion> versions(@PathVariable UUID id){return service.versions(id);}
  @GetMapping("/{id}/versions/{versionId}/fields") public List<com.fpt.workflow.form.domain.FormField> fields(@PathVariable UUID id,@PathVariable UUID versionId){return service.fields(id,versionId);}
}
