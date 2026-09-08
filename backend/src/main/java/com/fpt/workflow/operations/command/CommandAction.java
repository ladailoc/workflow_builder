package com.fpt.workflow.operations.command;

@FunctionalInterface
public interface CommandAction {

  CommandCompletion execute();
}
