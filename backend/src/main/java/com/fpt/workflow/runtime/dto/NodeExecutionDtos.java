package com.fpt.workflow.runtime.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import java.time.Instant;
import java.util.UUID;

public final class NodeExecutionDtos {

  private NodeExecutionDtos() {}

  public record View(
      UUID id,
      UUID eventId,
      UUID nodeDefinitionId,
      String activationKey,
      UUID cycleId,
      int iteration,
      String pathToken,
      String itemToken,
      UUID splitScopeId,
      UUID joinScopeId,
      NodeExecutionStatus status,
      RuntimeWaitReason waitReason,
      String outcomePort,
      JsonNode inputJson,
      JsonNode outputJson,
      JsonNode errorJson,
      UUID startedTicketRevisionId,
      Instant createdAt,
      Instant startedAt,
      Instant endedAt,
      long lockVersion) {

    public static View from(NodeExecution execution) {
      return new View(
          execution.getId(),
          execution.getEventId(),
          execution.getNodeDefinitionId(),
          execution.getActivationKey(),
          execution.getCycleId(),
          execution.getIteration(),
          execution.getPathToken(),
          execution.getItemToken(),
          execution.getSplitScopeId(),
          execution.getJoinScopeId(),
          execution.getStatus(),
          execution.getWaitReason(),
          execution.getOutcomePort(),
          execution.getInputJson(),
          execution.getOutputJson(),
          execution.getErrorJson(),
          execution.getStartedTicketRevisionId(),
          execution.getCreatedAt(),
          execution.getStartedAt(),
          execution.getEndedAt(),
          execution.getLockVersion());
    }
  }
}
