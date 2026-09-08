package com.fpt.workflow.security.audit;

import com.fpt.workflow.security.RoleKey;
import java.util.Set;
import java.util.UUID;

public record AuditPrincipal(UUID actorId, String principalName, Set<RoleKey> roles) {

  public AuditPrincipal {
    roles = Set.copyOf(roles);
  }
}
