package com.fpt.workflow.security;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

public record AuthenticatedActorPrincipal(UUID actorId, String principalName) implements Principal {

  public AuthenticatedActorPrincipal {
    Objects.requireNonNull(actorId, "actorId");
    if (principalName == null || principalName.isBlank()) {
      throw new IllegalArgumentException("principalName must not be blank");
    }
    principalName = principalName.trim();
  }

  @Override
  public String getName() {
    return principalName;
  }
}
