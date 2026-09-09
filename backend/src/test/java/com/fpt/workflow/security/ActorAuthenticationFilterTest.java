package com.fpt.workflow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ActorAuthenticationFilterTest {

  private static final String ACTOR_ID = "10000000-0000-4000-8000-000000000001";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("actor_auth_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private MockMvc mockMvc;

  @Test
  void requestWithoutActorHeaderIsUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/request-types"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void requestWithActorHeaderIsAuthenticated() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/request-types")
                .header("X-Actor-Id", ACTOR_ID)
                .header("X-Actor-Name", "Alice User")
                .header("X-Actor-Roles", "USER"))
        .andExpect(status().isOk());
  }

  @Test
  void runtimeEndpointsReturnOkWithActorHeader() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/tickets").header("X-Actor-Id", ACTOR_ID).header("X-Actor-Roles", "USER"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/v1/events").header("X-Actor-Id", ACTOR_ID).header("X-Actor-Roles", "USER"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/v1/tasks").header("X-Actor-Id", ACTOR_ID).header("X-Actor-Roles", "USER"))
        .andExpect(status().isOk());
  }
}
