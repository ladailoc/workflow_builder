package com.fpt.workflow.operations.job;

public interface WorkflowJobHandler {
  String jobType();

  JobExecutionResult execute(WorkflowJob job);
}
