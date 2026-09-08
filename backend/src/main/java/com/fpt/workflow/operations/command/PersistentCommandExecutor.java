package com.fpt.workflow.operations.command;

import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PersistentCommandExecutor implements CommandExecutor {

  private static final String RESERVE_SQL =
      """
      INSERT INTO command_executions (
          id, scope_type, scope_id, command_id, command_type, actor_id,
          expected_version, request_hash, status, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?)
      ON CONFLICT (scope_type, scope_id, command_id) DO NOTHING
      """;

  private final CommandExecutionRepository repository;
  private final JdbcTemplate jdbcTemplate;
  private final ActorContextProvider actorContextProvider;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;

  public PersistentCommandExecutor(
      CommandExecutionRepository repository,
      JdbcTemplate jdbcTemplate,
      ActorContextProvider actorContextProvider,
      UuidGenerator uuidGenerator,
      PlatformClock clock) {
    this.repository = repository;
    this.jdbcTemplate = jdbcTemplate;
    this.actorContextProvider = actorContextProvider;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
  }

  @Override
  @TransactionalCommand
  public CommandExecutionResult execute(CommandInvocation invocation, CommandAction action) {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(action, "action");
    ActorContext actor = actorContextProvider.requireActor();
    int inserted =
        jdbcTemplate.update(
            RESERVE_SQL,
            uuidGenerator.generate(),
            invocation.scopeType(),
            invocation.scopeId(),
            invocation.commandId().value(),
            invocation.commandType(),
            actor.actorId(),
            invocation.expectedVersion(),
            invocation.requestHash(),
            Timestamp.from(clock.now()));

    CommandExecution execution = requireExecution(invocation);
    if (inserted == 0) {
      return replay(execution, invocation, actor);
    }

    CommandCompletion completion = Objects.requireNonNull(action.execute(), "command completion");
    execution.succeed(completion.resultJson(), completion.resultMetadataJson(), clock.now());
    repository.saveAndFlush(execution);
    return CommandExecutionResult.from(execution, false);
  }

  private CommandExecutionResult replay(
      CommandExecution execution, CommandInvocation invocation, ActorContext actor) {
    if (!execution.matches(
        invocation.commandType(),
        actor.actorId(),
        invocation.expectedVersion(),
        invocation.requestHash())) {
      throw new CommandConflictException(
          "COMMAND_ID_REUSE_CONFLICT",
          "The scoped command id was already used for a different command request");
    }
    if (execution.getStatus() != CommandExecutionStatus.SUCCEEDED) {
      throw new CommandConflictException(
          "COMMAND_EXECUTION_NOT_REPLAYABLE",
          "The scoped command exists but has no successful result to replay");
    }
    return CommandExecutionResult.from(execution, true);
  }

  private CommandExecution requireExecution(CommandInvocation invocation) {
    return repository
        .findByScopeTypeAndScopeIdAndCommandId(
            invocation.scopeType(), invocation.scopeId(), invocation.commandId().value())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Reserved command execution could not be read in its transaction"));
  }
}
