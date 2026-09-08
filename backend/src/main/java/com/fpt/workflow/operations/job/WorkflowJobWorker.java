package com.fpt.workflow.operations.job;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WorkflowJobWorker {
  private final WorkflowJobTransactions transactions;
  private final WorkflowJobHandlerRegistry handlers;

  public WorkflowJobWorker(
      WorkflowJobTransactions transactions, WorkflowJobHandlerRegistry handlers) {
    this.transactions = transactions;
    this.handlers = handlers;
  }

  public int runOnce(String worker, int batch, Duration lease) {
    List<WorkflowJob> claimed = transactions.claim(worker, batch, lease);
    for (WorkflowJob job : claimed) {
      JobExecutionResult result;
      try {
        result = handlers.require(job.getJobType()).execute(job);
      } catch (Exception ex) {
        result =
            JobExecutionResult.retry(
                Duration.ZERO, JsonNodeFactory.instance.objectNode().put("message", safe(ex)));
      }
      if (result instanceof JobExecutionResult.Success) transactions.complete(job.getId(), worker);
      else if (result instanceof JobExecutionResult.Retry retry)
        transactions.fail(job.getId(), worker, retry.error(), retry.delay(), false);
      else
        transactions.fail(
            job.getId(),
            worker,
            ((JobExecutionResult.PermanentFailure) result).error(),
            Duration.ZERO,
            true);
    }
    return claimed.size();
  }

  private static String safe(Exception ex) {
    String m = ex.getMessage();
    return m == null ? ex.getClass().getSimpleName() : m.substring(0, Math.min(m.length(), 1000));
  }
}
