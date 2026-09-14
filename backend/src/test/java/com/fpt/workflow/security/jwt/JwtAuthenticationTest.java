package com.fpt.workflow.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.security.AuthenticatedActorPrincipal;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.security.web.ActorAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationTest {

  @Test
  @DisplayName("Missing JWT secret fails immediately in staging")
  void missingJwtSecretFailsInStaging() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("staging");

    assertThatThrownBy(() -> new JwtTokenService("", objectMapper, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT signing secret is required");
  }

  private final ObjectMapper objectMapper = new ObjectMapper();
  private JwtTokenService tokenService;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    tokenService =
        new JwtTokenService(
            "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-safety!",
            objectMapper);
  }

  @Test
  @DisplayName("Valid JWT token parses and creates authenticated actor with correct roles")
  void validTokenAuthenticatesCorrectly() {
    UUID actorId = UUID.randomUUID();
    String token =
        tokenService.createToken(
            actorId, "Alice Approver", List.of("USER", "APPROVER"), List.of("TICKET.READ"), Duration.ofHours(1));

    Authentication auth = tokenService.parseAndVerify(token);
    assertThat(auth).isNotNull();
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getPrincipal()).isInstanceOf(AuthenticatedActorPrincipal.class);

    AuthenticatedActorPrincipal principal = (AuthenticatedActorPrincipal) auth.getPrincipal();
    assertThat(principal.actorId()).isEqualTo(actorId);
    assertThat(principal.getName()).isEqualTo("Alice Approver");

    assertThat(auth.getAuthorities())
        .extracting("authority")
        .contains("ROLE_USER", "ROLE_APPROVER", "PERM_TICKET.READ");
  }

  @Test
  @DisplayName("Malformed JWT token throws BadCredentialsException")
  void malformedTokenThrows() {
    assertThatThrownBy(() -> tokenService.parseAndVerify("not.a.valid.jwt.token"))
        .isInstanceOf(BadCredentialsException.class);
    assertThatThrownBy(() -> tokenService.parseAndVerify("invalid-token"))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  @DisplayName("Tampered JWT signature is rejected")
  void tamperedSignatureThrows() {
    UUID actorId = UUID.randomUUID();
    String token =
        tokenService.createToken(actorId, "Bob", List.of("USER"), List.of(), Duration.ofHours(1));

    String[] parts = token.split("\\.");
    // Tamper with signature
    String tamperedToken = parts[0] + "." + parts[1] + ".tampered" + parts[2].substring(8);

    assertThatThrownBy(() -> tokenService.parseAndVerify(tamperedToken))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessageContaining("Invalid or tampered JWT signature");
  }

  @Test
  @DisplayName("Expired JWT token is rejected")
  void expiredTokenThrows() {
    UUID actorId = UUID.randomUUID();
    String token =
        tokenService.createToken(
            actorId, "Charlie", List.of("USER"), List.of(), Duration.ofSeconds(-10));

    assertThatThrownBy(() -> tokenService.parseAndVerify(token))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessageContaining("expired");
  }

  @Test
  @DisplayName("JwtAuthenticationFilter populates SecurityContext for valid Bearer token")
  void filterAuthenticatesBearerToken() throws ServletException, IOException {
    UUID actorId = UUID.randomUUID();
    String token =
        tokenService.createToken(actorId, "David", List.of("ADMIN"), List.of(), Duration.ofHours(1));

    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenService);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getAuthorities()).extracting("authority").contains("ROLE_ADMIN");
  }

  @Test
  @DisplayName("ActorAuthenticationFilter ignores X-Actor headers when disabled")
  void actorAuthenticationFilterDisabledIgnoresHeaders() throws ServletException, IOException {
    ActorAuthenticationFilter filter = new ActorAuthenticationFilter(false); // disabled (e.g. prod/staging)

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ActorAuthenticationFilter.ACTOR_ID_HEADER, UUID.randomUUID().toString());
    request.addHeader(ActorAuthenticationFilter.ACTOR_ROLES_HEADER, "ADMIN");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  @DisplayName("Caller cannot become ADMIN via headers when JWT specifies USER")
  void callerCannotEscalatePrivilegesWithHeaders() throws ServletException, IOException {
    UUID actorId = UUID.randomUUID();
    String userToken =
        tokenService.createToken(actorId, "Eve", List.of("USER"), List.of(), Duration.ofHours(1));

    JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(tokenService);
    ActorAuthenticationFilter actorFilter = new ActorAuthenticationFilter(false);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + userToken);
    request.addHeader(ActorAuthenticationFilter.ACTOR_ROLES_HEADER, "ADMIN"); // Attacker tries header escalation

    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> {};

    jwtFilter.doFilter(request, response, chain);
    actorFilter.doFilter(request, response, chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getAuthorities())
        .extracting("authority")
        .contains("ROLE_USER")
        .doesNotContain("ROLE_ADMIN");
  }
}
