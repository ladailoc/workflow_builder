package com.fpt.workflow.security.testing;

import com.fpt.workflow.security.AuthenticatedActorPrincipal;
import com.fpt.workflow.security.PermissionKey;
import com.fpt.workflow.security.RoleKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

public final class WithMockActorSecurityContextFactory
    implements WithSecurityContextFactory<WithMockActor> {

  @Override
  public SecurityContext createSecurityContext(WithMockActor actor) {
    var authorities = new ArrayList<SimpleGrantedAuthority>();
    Arrays.stream(actor.roles())
        .map(RoleKey::of)
        .map(RoleKey::authority)
        .map(SimpleGrantedAuthority::new)
        .forEach(authorities::add);
    Arrays.stream(actor.permissions())
        .map(PermissionKey::of)
        .map(PermissionKey::authority)
        .map(SimpleGrantedAuthority::new)
        .forEach(authorities::add);

    var principal =
        new AuthenticatedActorPrincipal(UUID.fromString(actor.actorId()), actor.principalName());
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(principal, "N/A", authorities);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    return context;
  }
}
