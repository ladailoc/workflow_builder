package com.fpt.workflow.operations;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.operations.command.CommandCompletion;
import com.fpt.workflow.operations.command.CommandExecutionRepository;
import com.fpt.workflow.operations.command.CommandExecutionResult;
import com.fpt.workflow.operations.command.CommandExecutor;
import com.fpt.workflow.operations.command.CommandInvocation;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@Import(CommandAuditPersistenceIT.ActorConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CommandAuditPersistenceIT {

  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_command_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private CommandExecutor commandExecutor;
  @Autowired private CommandExecutionRepository commandExecutionRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void serializesConcurrentDuplicateCommandsAndExecutesTheActionOnce() throws Exception {
    CommandInvocation invocation =
        invocation(UUID.randomUUID(), UUID.randomUUID(), "concurrent-hash");
    AtomicInteger actionCalls = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
      Future<CommandExecutionResult> first =
          executorService.submit(() -> executeAfterBarrier(invocation, actionCalls, ready, start));
      Future<CommandExecutionResult> second =
          executorService.submit(() -> executeAfterBarrier(invocation, actionCalls, ready, start));
      assertThat(ready.await(5, SECONDS)).isTrue();
      start.countDown();

      CommandExecutionResult firstResult = first.get(10, SECONDS);
      CommandExecutionResult secondResult = second.get(10, SECONDS);

      assertThat(actionCalls).hasValue(1);
      assertThat(firstResult.executionId()).isEqualTo(secondResult.executionId());
      assertThat(firstResult.resultJson()).isEqualTo(secondResult.resultJson());
      assertThat(firstResult.replayed()).isNotEqualTo(secondResult.replayed());
      assertThat(
              commandExecutionRepository.findByScopeTypeAndScopeIdAndCommandId(
                  invocation.scopeType(), invocation.scopeId(), invocation.commandId().value()))
          .isPresent();
    }
  }

  @Test
  void returnsOriginalResultAndRejectsCommandIdReuseWithDifferentRequest() {
    UUID scopeId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();
    CommandInvocation invocation = invocation(scopeId, commandId, "original-hash");
    AtomicBoolean duplicateActionCalled = new AtomicBoolean();
    CommandExecutionResult original =
        commandExecutor.execute(
            invocation,
            () ->
                new CommandCompletion(
                    objectMapper.createObjectNode().put("result", "original"),
                    objectMapper.createObjectNode().put("version", 7)));

    CommandExecutionResult duplicate =
        commandExecutor.execute(
            invocation,
            () -> {
              duplicateActionCalled.set(true);
              return new CommandCompletion(
                  objectMapper.createObjectNode().put("result", "replacement"),
                  objectMapper.createObjectNode());
            });

    assertThat(duplicateActionCalled).isFalse();
    assertThat(duplicate.replayed()).isTrue();
    assertThat(duplicate.executionId()).isEqualTo(original.executionId());
    assertThat(duplicate.resultJson().path("result").asText()).isEqualTo("original");
    assertThat(duplicate.resultMetadataJson().path("version").asInt()).isEqualTo(7);

    CommandInvocation conflicting = invocation(scopeId, commandId, "different-hash");
    assertThatThrownBy(
            () ->
                commandExecutor.execute(
                    conflicting,
                    () ->
                        new CommandCompletion(
                            objectMapper.createObjectNode(), objectMapper.createObjectNode())))
        .isInstanceOf(CommandConflictException.class)
        .hasMessageContaining("different command request");

    CommandExecutionResult differentScope =
        commandExecutor.execute(
            invocation(UUID.randomUUID(), commandId, "different-scope-hash"),
            () ->
                new CommandCompletion(
                    objectMapper.createObjectNode().put("result", "different-scope"),
                    objectMapper.createObjectNode()));
    assertThat(differentScope.replayed()).isFalse();
  }

  @Test
  void keepsAuditAndTerminalCommandHistoryAppendOnly() {
    UUID aggregateId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();
    CommandExecutionResult command =
        commandExecutor.execute(
            invocation(aggregateId, commandId, "audit-hash"),
            () ->
                new CommandCompletion(
                    objectMapper.createObjectNode().put("status", "done"),
                    objectMapper.createObjectNode()));
    AuditEvent auditEvent =
        auditEventRepository.saveAndFlush(
            AuditEvent.record(
                UUID.randomUUID(),
                "TICKET",
                aggregateId,
                "TICKET_SUBMITTED",
                ACTOR_ID,
                UUID.randomUUID(),
                new CorrelationId(UUID.randomUUID()),
                new CommandId(commandId),
                objectMapper.createObjectNode().put("revision", 1),
                Instant.now()));

    assertThat(
            auditEventRepository.findAllByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(
                "TICKET", aggregateId))
        .extracting(AuditEvent::getId)
        .containsExactly(auditEvent.getId());
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE audit_events SET event_type = 'CHANGED' WHERE id = ?",
                    auditEvent.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM audit_events WHERE id = ?", auditEvent.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE command_executions SET status = 'IN_PROGRESS', result_json = NULL, "
                        + "completed_at = NULL WHERE id = ?",
                    command.executionId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM command_executions WHERE id = ?", command.executionId()))
        .isInstanceOf(DataAccessException.class);
  }

  private CommandExecutionResult executeAfterBarrier(
      CommandInvocation invocation,
      AtomicInteger actionCalls,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, SECONDS)) {
      throw new IllegalStateException("Concurrent command start barrier timed out");
    }
    return commandExecutor.execute(
        invocation,
        () -> {
          int call = actionCalls.incrementAndGet();
          LockSupport.parkNanos(200_000_000L);
          return new CommandCompletion(
              objectMapper.createObjectNode().put("call", call),
              objectMapper.createObjectNode().put("source", "command-action"));
        });
  }

  private CommandInvocation invocation(UUID scopeId, UUID commandId, String requestHash) {
    return new CommandInvocation(
        "TICKET", scopeId, new CommandId(commandId), "SUBMIT_TICKET", 3L, requestHash);
  }

  @TestConfiguration
  static class ActorConfiguration {

    @Bean
    @Primary
    ActorContextProvider fixedActorContextProvider() {
      ActorContext actor = new ActorContext(ACTOR_ID, "command-test", Set.of(), Set.of());
      return new ActorContextProvider() {
        @Override
        public Optional<ActorContext> currentActor() {
          return Optional.of(actor);
        }

        @Override
        public ActorContext requireActor() {
          return actor;
        }
      };
    }
  }
}
