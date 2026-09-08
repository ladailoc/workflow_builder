package com.fpt.workflow.operations.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandDomainTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

  @Test
  void completesOnceAndPreservesReusableResult() {
    UUID actorId = UUID.randomUUID();
    CommandExecution execution =
        CommandExecution.start(
            UUID.randomUUID(),
            "ticket",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "submit_ticket",
            actorId,
            3L,
            "request-hash",
            OBJECT_MAPPER.createObjectNode(),
            NOW);

    execution.succeed(
        OBJECT_MAPPER.createObjectNode().put("ticketId", "T-1"),
        OBJECT_MAPPER.createObjectNode().put("resultVersion", 4),
        NOW.plusSeconds(1));

    assertThat(execution.getStatus()).isEqualTo(CommandExecutionStatus.SUCCEEDED);
    assertThat(execution.getResultJson().path("ticketId").asText()).isEqualTo("T-1");
    assertThat(execution.matches("SUBMIT_TICKET", actorId, 3L, "request-hash")).isTrue();
    assertThatThrownBy(
            () ->
                execution.succeed(
                    OBJECT_MAPPER.createObjectNode(),
                    OBJECT_MAPPER.createObjectNode(),
                    NOW.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validatesCommandIdentityAndResultMetadata() {
    assertThatThrownBy(
            () ->
                new CommandInvocation(
                    "ticket",
                    UUID.randomUUID(),
                    new com.fpt.workflow.shared.domain.CommandId(UUID.randomUUID()),
                    "submit_ticket",
                    -1L,
                    "hash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CommandCompletion(
                    OBJECT_MAPPER.createObjectNode(), OBJECT_MAPPER.createArrayNode()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
