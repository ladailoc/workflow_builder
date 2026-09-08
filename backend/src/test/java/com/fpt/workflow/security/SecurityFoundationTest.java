package com.fpt.workflow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fpt.workflow.security.audit.AuditPrincipalProvider;
import com.fpt.workflow.security.config.MethodSecurityConfiguration;
import com.fpt.workflow.security.config.WebSecurityConfiguration;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.security.web.ProblemAccessDeniedHandler;
import com.fpt.workflow.security.web.ProblemAuthenticationEntryPoint;
import com.fpt.workflow.security.web.SecurityProblemWriter;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.ApiExceptionHandler;
import com.fpt.workflow.shared.api.ApiProblemFactory;
import com.fpt.workflow.shared.api.RequestCorrelationFilter;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.testing.FixedPlatformClock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
    controllers = SecurityFoundationTest.SecurityFixtureController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({
  SpringSecurityActorContextProvider.class,
  PlatformAuthorization.class,
  AuditPrincipalProvider.class,
  MethodSecurityConfiguration.class,
  WebSecurityConfiguration.class,
  ProblemAuthenticationEntryPoint.class,
  ProblemAccessDeniedHandler.class,
  SecurityProblemWriter.class,
  ApiExceptionHandler.class,
  ApiProblemFactory.class,
  RequestCorrelationFilter.class,
  SecurityFoundationTest.SecurityFixtureController.class,
  SecurityFoundationTest.TestBeans.class
})
class SecurityFoundationTest {

  private static final UUID AUTHENTICATED_ACTOR_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID REQUEST_ACTOR_ID =
      UUID.fromString("90000000-0000-4000-8000-000000000009");

  @Autowired private MockMvc mockMvc;

  @Test
  void unauthenticatedRequestReturns401Problem() throws Exception {
    mockMvc
        .perform(get("/api/v1/_test/security/admin"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  @WithMockActor(roles = "USER")
  void authenticatedActorWithoutRequiredRoleReturns403Problem() throws Exception {
    mockMvc
        .perform(get("/api/v1/_test/security/admin"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @WithMockActor(roles = {"USER", "ADMIN"})
  void authorizedRequestUsesPrincipalActorAndIgnoresActorIdParameter() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/_test/security/admin").queryParam("actorId", REQUEST_ACTOR_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actorId").value(AUTHENTICATED_ACTOR_ID.toString()))
        .andExpect(jsonPath("$.auditActorId").value(AUTHENTICATED_ACTOR_ID.toString()));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {

    @Bean
    PlatformClock platformClock() {
      return new FixedPlatformClock(Instant.parse("2026-09-07T03:00:00Z"));
    }

    @Bean
    UuidGenerator uuidGenerator() {
      return UUID::randomUUID;
    }
  }

  @RestController
  @RequestMapping("/api/v1/_test/security")
  public static class SecurityFixtureController {

    private final ActorContextProvider actorContextProvider;
    private final AuditPrincipalProvider auditPrincipalProvider;

    public SecurityFixtureController(
        ActorContextProvider actorContextProvider, AuditPrincipalProvider auditPrincipalProvider) {
      this.actorContextProvider = actorContextProvider;
      this.auditPrincipalProvider = auditPrincipalProvider;
    }

    @GetMapping("/admin")
    @PreAuthorize("@platformAuthorization.hasRole('ADMIN')")
    public ActorResponse admin(@RequestParam(required = false) UUID actorId) {
      ActorContext actor = actorContextProvider.requireActor();
      return new ActorResponse(actor.actorId(), auditPrincipalProvider.requireCurrent().actorId());
    }
  }

  public record ActorResponse(UUID actorId, UUID auditActorId) {}
}
