package com.fpt.workflow.security.web;

import com.fpt.workflow.security.AuthenticatedActorPrincipal;
import com.fpt.workflow.security.PermissionKey;
import com.fpt.workflow.security.RoleKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class ActorAuthenticationFilter extends OncePerRequestFilter {

  public static final String ACTOR_ID_HEADER = "X-Actor-Id";
  public static final String ACTOR_NAME_HEADER = "X-Actor-Name";
  public static final String ACTOR_ROLES_HEADER = "X-Actor-Roles";
  public static final String ACTOR_PERMISSIONS_HEADER = "X-Actor-Permissions";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String actorIdHeader = request.getHeader(ACTOR_ID_HEADER);
    if (actorIdHeader != null && !actorIdHeader.isBlank()) {
      UUID actorId = parseActorId(actorIdHeader.trim());
      if (actorId != null) {
        String actorName = request.getHeader(ACTOR_NAME_HEADER);
        if (actorName == null || actorName.isBlank()) {
          actorName = "Workflow Actor";
        }

        var authorities = new ArrayList<SimpleGrantedAuthority>();
        String rolesHeader = request.getHeader(ACTOR_ROLES_HEADER);
        if (rolesHeader != null && !rolesHeader.isBlank()) {
          Arrays.stream(rolesHeader.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .forEach(
                  role -> {
                    try {
                      authorities.add(new SimpleGrantedAuthority(RoleKey.of(role).authority()));
                    } catch (IllegalArgumentException ignored) {
                    }
                  });
        }
        if (authorities.isEmpty()) {
          authorities.add(new SimpleGrantedAuthority(RoleKey.USER.authority()));
        }

        String permissionsHeader = request.getHeader(ACTOR_PERMISSIONS_HEADER);
        if (permissionsHeader != null && !permissionsHeader.isBlank()) {
          Arrays.stream(permissionsHeader.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .forEach(
                  perm -> {
                    try {
                      authorities.add(
                          new SimpleGrantedAuthority(PermissionKey.of(perm).authority()));
                    } catch (IllegalArgumentException ignored) {
                    }
                  });
        }

        AuthenticatedActorPrincipal principal =
            new AuthenticatedActorPrincipal(actorId, actorName.trim());
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, "N/A", authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
      }
    }

    filterChain.doFilter(request, response);
  }

  private UUID parseActorId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ex) {
      return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }
  }
}
