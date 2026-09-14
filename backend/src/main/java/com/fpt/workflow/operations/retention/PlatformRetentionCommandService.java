package com.fpt.workflow.operations.retention;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.operations.job.WorkflowJob;
import com.fpt.workflow.operations.job.WorkflowJobTransactions;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.time.PlatformClock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformRetentionCommandService {

  public static final String JOB_TYPE = "PLATFORM_RETENTION";
  private final WorkflowJobTransactions jobs;
  private final ActorContextProvider actors;
  private final ObjectMapper mapper;
  private final PlatformClock clock;

  public PlatformRetentionCommandService(
      WorkflowJobTransactions jobs,
      ActorContextProvider actors,
      ObjectMapper mapper,
      PlatformClock clock) {
    this.jobs = jobs;
    this.actors = actors;
    this.mapper = mapper;
    this.clock = clock;
  }

  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  @Transactional
  public RetentionJobSubmission enqueue(
      List<PlatformRetentionExecutor.RetentionCandidate> candidates,
      CorrelationId correlationId,
      CommandId commandId) {
    if (candidates == null || candidates.isEmpty()) {
      throw new IllegalArgumentException("At least one retention candidate is required");
    }
    ActorContext actor = actors.requireActor();
    ObjectNode payload = mapper.createObjectNode();
    payload.put("actorId", actor.actorId().toString());
    payload.put("principalName", actor.principalName());
    payload.put("correlationId", correlationId.value().toString());
    payload.put("commandId", commandId.value().toString());
    ArrayNode values = payload.putArray("candidates");
    for (PlatformRetentionExecutor.RetentionCandidate candidate : candidates) {
      ObjectNode node = values.addObject();
      node.put("category", candidate.category().name());
      node.put("aggregateType", candidate.aggregateType());
      node.put("aggregateId", candidate.aggregateId().toString());
      node.put("artifactTime", candidate.artifactTime().toString());
      node.put("runtimeHistoryReferenced", candidate.runtimeHistoryReferenced());
    }
    String dedupKey = "platform-retention:" + commandId.value();
    WorkflowJob job =
        jobs.enqueue(
            JOB_TYPE,
            "RETENTION_BATCH",
            commandId.value(),
            payload,
            3,
            clock.now(),
            dedupKey);
    return new RetentionJobSubmission(job.getId(), job.getStatus().name(), dedupKey);
  }

  public record RetentionJobSubmission(UUID jobId, String status, String dedupKey) {}
}
