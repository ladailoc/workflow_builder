package com.fpt.workflow.security;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public final class SpringSecurityActorContextProvider implements ActorContextProvider {

  @Override
  public Optional<ActorContext> currentActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken
        || !(authentication.getPrincipal() instanceof AuthenticatedActorPrincipal principal)) {
      return Optional.empty();
    }

    Set<RoleKey> roles =
        authentication.getAuthorities().stream()
            .map(authority -> RoleKey.fromAuthority(authority.getAuthority()))
            .flatMap(Optional::stream)
            .collect(Collectors.toUnmodifiableSet());
    Set<PermissionKey> permissions =
        authentication.getAuthorities().stream()
            .map(authority -> PermissionKey.fromAuthority(authority.getAuthority()))
            .flatMap(Optional::stream)
            .collect(Collectors.toUnmodifiableSet());

    return Optional.of(
        new ActorContext(principal.actorId(), principal.principalName(), roles, permissions));
  }

  @Override
  public ActorContext requireActor() {
    return currentActor()
        .orElseThrow(
            () ->
                new AuthenticationCredentialsNotFoundException(
                    "An authenticated workflow actor is required"));
  }
}
