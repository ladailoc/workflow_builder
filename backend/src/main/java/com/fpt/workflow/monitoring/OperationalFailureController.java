package com.fpt.workflow.monitoring;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/failures")
public class OperationalFailureController {
  private final OperationalFailureService service;

  public OperationalFailureController(OperationalFailureService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public List<OperationalFailureService.OperationalFailure> list() {
    return service.list();
  }
}
