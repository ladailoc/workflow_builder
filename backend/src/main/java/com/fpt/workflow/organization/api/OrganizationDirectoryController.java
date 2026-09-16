package com.fpt.workflow.organization.api;

import com.fpt.workflow.organization.service.OrganizationDirectoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative, read-only directory surface. Mutations stay behind dedicated domain commands. */
@RestController
@RequestMapping("/api/v1/organization")
@PreAuthorize("hasAnyRole('ADMIN','WORKFLOW_OWNER')")
public class OrganizationDirectoryController {
  private final OrganizationDirectoryService directory;

  public OrganizationDirectoryController(OrganizationDirectoryService directory) {
    this.directory = directory;
  }

  @GetMapping("/directory")
  public OrganizationDirectoryService.DirectorySnapshot directory() {
    return directory.snapshot();
  }
}
