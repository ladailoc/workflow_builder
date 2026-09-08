package com.fpt.workflow.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ActorContext(
    UUID actorId, String principalName, Set<RoleKey> roles, Set<PermissionKey> permissions) {

  public ActorContext {
    Objects.requireNonNull(actorId, "actorId");
    if (principalName == null || principalName.isBlank()) {
      throw new IllegalArgumentException("principalName must not be blank");
    }
    principalName = principalName.trim();
    roles = Set.copyOf(roles);
    permissions = Set.copyOf(permissions);
  }

  public boolean hasRole(RoleKey role) {
    return roles.contains(role);
  }

  public boolean hasPermission(PermissionKey permission) {
    return permissions.contains(permission);
  }
}
