package com.fpt.workflow.shared.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fpt.workflow.testing.FixedPlatformClock;
import com.fpt.workflow.testing.FixedUuidGenerator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

  private static final Instant NOW = Instant.parse("2026-09-07T03:00:00Z");
  private static final UUID GENERATED_CORRELATION_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID GENERATED_REQUEST_ID =
      UUID.fromString("20000000-0000-4000-8000-000000000002");

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    var clock = new FixedPlatformClock(NOW);
    var uuidGenerator = new FixedUuidGenerator(GENERATED_CORRELATION_ID, GENERATED_REQUEST_ID);
    var objectMapper =
        Jackson2ObjectMapperBuilder.json()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new FixtureController())
            .setControllerAdvice(new ApiExceptionHandler(new ApiProblemFactory(clock)))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .addFilters(new RequestCorrelationFilter(uuidGenerator))
            .build();
  }

  @ParameterizedTest
  @CsvSource({
    "bad-request,400,INVALID_REQUEST",
    "forbidden,403,FORBIDDEN",
    "missing,404,TICKET_NOT_FOUND",
    "conflict,409,COMMAND_STATE_CONFLICT",
    "optimistic-lock,409,OPTIMISTIC_LOCK_CONFLICT",
    "unprocessable,422,COMMAND_PRECONDITION_FAILED"
  })
  void mapsPlatformHttpSemantics(String kind, int expectedStatus, String expectedCode)
      throws Exception {
    mockMvc
        .perform(get("/api/v1/_test/errors/{kind}", kind))
        .andExpect(status().is(expectedStatus))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(expectedStatus))
        .andExpect(jsonPath("$.code").value(expectedCode))
        .andExpect(jsonPath("$.correlationId").value(GENERATED_CORRELATION_ID.toString()))
        .andExpect(jsonPath("$.requestId").value(GENERATED_REQUEST_ID.toString()))
        .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
        .andExpect(
            header()
                .string(
                    RequestCorrelationFilter.CORRELATION_ID_HEADER,
                    GENERATED_CORRELATION_ID.toString()))
        .andExpect(
            header()
                .string(
                    RequestCorrelationFilter.REQUEST_ID_HEADER, GENERATED_REQUEST_ID.toString()));
  }

  @Test
  void returnsStructuredValidationErrorsWithoutRejectedValues() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/_test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[0].code").value("NotBlank"))
        .andExpect(jsonPath("$.errors[0].message").value("must not be blank"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
  }

  @Test
  void mapsMalformedJsonToBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/_test/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  void propagatesOnlyUuidCorrelationIdsAndAlwaysGeneratesARequestId() throws Exception {
    UUID incomingCorrelationId = UUID.fromString("30000000-0000-4000-8000-000000000003");

    mockMvc
        .perform(
            get("/api/v1/_test/errors/bad-request")
                .header(RequestCorrelationFilter.CORRELATION_ID_HEADER, incomingCorrelationId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.correlationId").value(incomingCorrelationId.toString()))
        .andExpect(jsonPath("$.requestId").value(GENERATED_CORRELATION_ID.toString()))
        .andExpect(
            header()
                .string(
                    RequestCorrelationFilter.CORRELATION_ID_HEADER,
                    incomingCorrelationId.toString()));
  }

  @RestController
  @RequestMapping("/api/v1/_test")
  private static final class FixtureController {

    @GetMapping("/errors/{kind}")
    void error(@PathVariable String kind) {
      switch (kind) {
        case "bad-request" -> throw new BadRequestException("INVALID_REQUEST", "Invalid request");
        case "forbidden" -> throw new AccessDeniedException("Denied");
        case "missing" ->
            throw new ResourceNotFoundException("TICKET_NOT_FOUND", "Ticket was not found");
        case "conflict" ->
            throw new CommandConflictException(
                "COMMAND_STATE_CONFLICT", "Command is invalid for current state");
        case "optimistic-lock" -> throw new OptimisticLockingFailureException("Stale write");
        case "unprocessable" ->
            throw new UnprocessableCommandException(
                "COMMAND_PRECONDITION_FAILED", "Business precondition failed");
        default -> throw new IllegalArgumentException("Unknown fixture");
      }
    }

    @PostMapping("/validate")
    void validate(@Valid @RequestBody TestCommand command) {}
  }

  private record TestCommand(@NotBlank String name) {}
}
