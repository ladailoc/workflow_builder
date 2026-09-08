package com.fpt.workflow.slanotification.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.operations.job.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class NotificationJobHandler implements WorkflowJobHandler {
  private final NotificationTransactions transactions;
  private final NotificationChannelRegistry channels;

  public NotificationJobHandler(
      NotificationTransactions transactions, NotificationChannelRegistry channels) {
    this.transactions = transactions;
    this.channels = channels;
  }

  @Override
  public String jobType() {
    return "NOTIFICATION_DELIVERY";
  }

  @Override
  public JobExecutionResult execute(WorkflowJob job) {
    UUID id = UUID.fromString(job.getPayloadJson().path("dispatchId").asText());
    var candidate = transactions.begin(id);
    if (candidate.isEmpty()) return JobExecutionResult.success();
    var dispatch = candidate.orElseThrow();
    try {
      channels.require(dispatch.getChannel()).send(dispatch);
      transactions.sent(id);
      return JobExecutionResult.success();
    } catch (Exception ex) {
      boolean dead = job.getAttempts() >= job.getMaxAttempts();
      var error = JsonNodeFactory.instance.objectNode().put("message", safe(ex));
      transactions.failed(id, error, dead);
      return dead
          ? JobExecutionResult.dead(error)
          : JobExecutionResult.retry(Duration.ofSeconds(30), error);
    }
  }

  private static String safe(Exception ex) {
    String value = ex.getMessage();
    return value == null
        ? ex.getClass().getSimpleName()
        : value.substring(0, Math.min(1000, value.length()));
  }
}
