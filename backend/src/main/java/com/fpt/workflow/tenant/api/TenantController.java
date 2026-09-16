package com.fpt.workflow.tenant.api;

import com.fpt.workflow.tenant.service.TenantAccessService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
@PreAuthorize("isAuthenticated()")
public class TenantController {
  private final TenantAccessService access;

  public TenantController(TenantAccessService access) { this.access = access; }

  @GetMapping
  public List<TenantAccessService.TenantOption> list() { return access.listVisible(); }
}
