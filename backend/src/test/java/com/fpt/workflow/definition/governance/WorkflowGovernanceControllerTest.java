package com.fpt.workflow.definition.governance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.testing.FixedPlatformClock;
import java.time.Instant;
import java.util.List;
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
    controllers = WorkflowGovernanceController.class,
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
  WorkflowGovernanceControllerTest.TestBeans.class
})
class WorkflowGovernanceControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean WorkflowSemanticDiffService diffService;
  @MockitoBean WorkflowRollbackService rollbackService;

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void diffReturnsSemanticDiffForAuthorizedActor() throws Exception {
    UUID fromVersionId = UUID.randomUUID();
    UUID toVersionId = UUID.randomUUID();
    var diffResult =
        new WorkflowSemanticDiffService.SemanticDiff(
            fromVersionId,
            toVersionId,
            List.of(
                new WorkflowSemanticDiffService.SemanticChange(
                    "node1", WorkflowSemanticDiffService.ChangeType.ADDED, null, null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    when(diffService.diff(fromVersionId, toVersionId)).thenReturn(diffResult);

    mockMvc
        .perform(get("/api/v1/workflow-versions/{from}/diff/{to}", fromVersionId, toVersionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fromVersionId").value(fromVersionId.toString()))
        .andExpect(jsonPath("$.toVersionId").value(toVersionId.toString()))
        .andExpect(jsonPath("$.nodes[0].resourceKey").value("node1"))
        .andExpect(jsonPath("$.nodes[0].changeType").value("ADDED"));

    verify(diffService).diff(fromVersionId, toVersionId);
  }

  @Test
  @WithMockActor(roles = "USER")
  void diffRejectsUnauthorizedActor() throws Exception {
    UUID fromVersionId = UUID.randomUUID();
    UUID toVersionId = UUID.randomUUID();

    mockMvc
        .perform(get("/api/v1/workflow-versions/{from}/diff/{to}", fromVersionId, toVersionId))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_OWNER")
  void rollbackDispatchesCommandAndReturnsNewMonotonicVersion() throws Exception {
    UUID sourceVersionId = UUID.randomUUID();
    UUID newVersionId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();

    when(rollbackService.rollback(eq(sourceVersionId), eq(new ExpectedVersion(2)), any()))
        .thenReturn(
            new WorkflowRollbackService.RollbackResult(
                sourceVersionId, newVersionId, 3, "checksum-3"));

    mockMvc
        .perform(
            post("/api/v1/workflow-versions/{source}/rollback", sourceVersionId)
                .header("If-Match", "2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandId\":\"" + commandId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceVersionId").value(sourceVersionId.toString()))
        .andExpect(jsonPath("$.publishedVersionId").value(newVersionId.toString()))
        .andExpect(jsonPath("$.versionNo").value(3))
        .andExpect(jsonPath("$.checksum").value("checksum-3"));

    verify(rollbackService).rollback(eq(sourceVersionId), eq(new ExpectedVersion(2)), any());
  }

  @Test
  @WithMockActor(roles = "WORKFLOW_EDITOR")
  void rollbackRejectsNonOwnerEditor() throws Exception {
    UUID sourceVersionId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/workflow-versions/{source}/rollback", sourceVersionId)
                .header("If-Match", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isForbidden());
  }

  @TestConfiguration
  static class TestBeans {
    @Bean
    PlatformClock clock() {
      return new FixedPlatformClock(Instant.parse("2026-09-08T00:00:00Z"));
    }

    @Bean
    UuidGenerator uuidGenerator() {
      return UUID::randomUUID;
    }
  }
}
