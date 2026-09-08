package com.fpt.workflow.operations.command;

public interface CommandExecutor {

  CommandExecutionResult execute(CommandInvocation invocation, CommandAction action);
}
