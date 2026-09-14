package com.fpt.workflow.operations.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.operations.job.JobExecutionResult;
import com.fpt.workflow.operations.job.WorkflowJob;
import com.fpt.workflow.operations.job.WorkflowJobHandler;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.PermissionKey;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlatformRetentionJobHandler implements WorkflowJobHandler {

  private final PlatformRetentionExecutor executor;

  public PlatformRetentionJobHandler(PlatformRetentionExecutor executor) {
    this.executor = executor;
  }

  @Override
  public String jobType() {
    return PlatformRetentionCommandService.JOB_TYPE;
  }

  @Override
  public JobExecutionResult execute(WorkflowJob job) {
    JsonNode payload = job.getPayloadJson();
    try {
      executor.executeFromDurableJob(
          candidates(payload.path("candidates")),
          actor(payload),
          new CorrelationId(UUID.fromString(payload.path("correlationId").asText())),
          new CommandId(UUID.fromString(payload.path("commandId").asText())));
      return JobExecutionResult.success();
    } catch (IllegalArgumentException ex) {
      return JobExecutionResult.dead(JsonNodeFactory.instance.objectNode().put("message", safe(ex)));
    }
  }

  private static ActorContext actor(JsonNode payload) {
    return new ActorContext(
        UUID.fromString(payload.path("actorId").asText()),
        payload.path("principalName").asText("retention-operator"),
        Set.of(RoleKey.OPERATOR),
        Set.of(PermissionKey.of("RETENTION_EXECUTE")));
  }

  private static java.util.List<PlatformRetentionExecutor.RetentionCandidate> candidates(
      JsonNode values) {
    if (!values.isArray() || values.isEmpty()) {
      throw new IllegalArgumentException("Retention job requires candidates");
    }
    java.util.List<PlatformRetentionExecutor.RetentionCandidate> candidates = new ArrayList<>();
    for (JsonNode value : values) {
      candidates.add(
          new PlatformRetentionExecutor.RetentionCandidate(
              RetentionCategory.valueOf(value.path("category").asText()),
              value.path("aggregateType").asText(),
              UUID.fromString(value.path("aggregateId").asText()),
              Instant.parse(value.path("artifactTime").asText()),
              value.path("runtimeHistoryReferenced").asBoolean()));
    }
    return candidates;
  }

  private static String safe(Exception ex) {
    String value = ex.getMessage();
    return value == null
        ? ex.getClass().getSimpleName()
        : value.substring(0, Math.min(1000, value.length()));
  }
}
