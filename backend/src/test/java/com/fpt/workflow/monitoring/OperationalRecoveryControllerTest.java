package com.fpt.workflow.monitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.operations.command.CommandExecutionResult;
import com.fpt.workflow.security.SpringSecurityActorContextProvider;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = OperationalRecoveryController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({
  SpringSecurityActorContextProvider.class,
  AuditPrincipalProvider.class,
  MethodSecurityConfiguration.class,
  WebSecurityConfiguration.class,
  ProblemAuthenticationEntryPoint.class,
  ProblemAccessDeniedHandler.class,
  SecurityProblemWriter.class,
  ApiExceptionHandler.class,
  ApiProblemFactory.class,
  RequestCorrelationFilter.class,
  OperationalRecoveryControllerTest.TestBeans.class
})
class OperationalRecoveryControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean OperationalRecoveryService service;

  @Test
  void unauthenticatedOperatorCommandReturns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/operations/jobs/{id}/retry", UUID.randomUUID())
                .header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockActor(roles = "USER")
  void nonOperatorCommandReturns403() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/operations/jobs/{id}/retry", UUID.randomUUID())
                .header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockActor(roles = "OPERATOR")
  void operatorCanRetryJobWithReasonAndExpectedVersion() throws Exception {
    UUID id = UUID.randomUUID();
    var result =
        new CommandExecutionResult(
            UUID.randomUUID(),
            JsonNodeFactory.instance.objectNode().put("status", "RETRY"),
            JsonNodeFactory.instance.objectNode(),
            false);
    when(service.retryJob(eq(id), eq(7L), any(), any())).thenReturn(result);

    mockMvc
        .perform(
            post("/api/v1/operations/jobs/{id}/retry", id)
                .header("If-Match", 7)
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultJson.status").value("RETRY"));
    verify(service).retryJob(eq(id), eq(7L), any(), any());
  }

  private String commandJson() {
    return """
        {"commandId":"%s","reason":"operator inspected incident","output":{}}
        """
        .formatted(UUID.randomUUID());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {
    @Bean
    PlatformClock platformClock() {
      return new FixedPlatformClock(Instant.parse("2026-09-09T00:00:00Z"));
    }

    @Bean
    UuidGenerator uuidGenerator() {
      return UUID::randomUUID;
    }
  }
}
