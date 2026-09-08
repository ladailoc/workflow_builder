package com.fpt.workflow.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component("platformAuthorization")
public final class PlatformAuthorization {

  private final ActorContextProvider actorContextProvider;

  public PlatformAuthorization(ActorContextProvider actorContextProvider) {
    this.actorContextProvider = actorContextProvider;
  }

  public boolean hasRole(String role) {
    try {
      RoleKey requiredRole = RoleKey.of(role);
      return actorContextProvider
          .currentActor()
          .map(actor -> actor.hasRole(requiredRole))
          .orElse(false);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  public boolean hasPermission(String permission) {
    try {
      PermissionKey requiredPermission = PermissionKey.of(permission);
      return actorContextProvider
          .currentActor()
          .map(actor -> actor.hasPermission(requiredPermission))
          .orElse(false);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  public void requireRole(RoleKey role) {
    if (!actorContextProvider.requireActor().hasRole(role)) {
      throw new AccessDeniedException("Required role is missing");
    }
  }

  public void requirePermission(PermissionKey permission) {
    if (!actorContextProvider.requireActor().hasPermission(permission)) {
      throw new AccessDeniedException("Required permission is missing");
    }
  }
}
