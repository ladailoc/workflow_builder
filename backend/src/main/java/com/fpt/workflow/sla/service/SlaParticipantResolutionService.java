package com.fpt.workflow.sla.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.participant.ParticipantResolverContext;
import com.fpt.workflow.resolver.participant.ParticipantResolverRegistry;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.task.domain.TaskExecution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Resolves SLA recipients through the common registry and stores immutable historical evidence. */
@Service
public class SlaParticipantResolutionService {

  private final NodeExecutionRepository executions;
  private final EventRepository events;
  private final ParticipantResolverRegistry resolvers;
  private final ParticipantSnapshotRepository snapshots;
  private final CanonicalDefinitionJson canonicalJson;
  private final UuidGenerator uuids;

  public SlaParticipantResolutionService(
      NodeExecutionRepository executions,
      EventRepository events,
      ParticipantResolverRegistry resolvers,
      ParticipantSnapshotRepository snapshots,
      CanonicalDefinitionJson canonicalJson,
      UuidGenerator uuids) {
    this.executions = executions;
    this.events = events;
    this.resolvers = resolvers;
    this.snapshots = snapshots;
    this.canonicalJson = canonicalJson;
    this.uuids = uuids;
  }

  public List<UUID> resolveAndSnapshot(
      TaskExecution task, JsonNode configuredResolver, ObjectNode fallback, String role, Instant at) {
    ObjectNode config = normalize(configuredResolver, fallback);
    List<com.fpt.workflow.resolver.participant.ParticipantResolverValidationIssue> issues =
        resolvers.validate(config);
    if (!issues.isEmpty()) {
      throw new IllegalStateException(issues.getFirst().message());
    }

    NodeExecution execution = executions.findById(task.getNodeExecutionId()).orElseThrow();
    Event event = events.findById(execution.getEventId()).orElseThrow();
    UUID reference = task.getAssigneeId() != null ? task.getAssigneeId() : event.getStartedBy();
    var result =
        resolvers.resolveResult(
            config.path("type").asText(),
            new ParticipantResolverContext(event.getStartedBy(), reference, null, config, at));
    if (!result.isResolved()) {
      throw new IllegalStateException(
          "SLA participant resolution failed: "
              + result.status()
              + (result.reason() == null ? "" : " - " + result.reason()));
    }

    String configHash = canonicalJson.checksum(canonicalJson.canonicalize(config));
    for (UUID userId : result.users().stream().distinct().toList()) {
      ObjectNode evidence = JsonNodeFactory.instance.objectNode();
      evidence.put("resolvedUserId", userId.toString());
      evidence.put("resolverType", result.resolverType());
      evidence.put("role", role);
      snapshots.save(
          ParticipantSnapshot.create(
              uuids.generate(),
              event.getId(),
              execution.getId(),
              task.getItemExecutionId(),
              result.resolverType(),
              configHash,
              config,
              ParticipantResolutionStatus.RESOLVED,
              "CURRENT_ASSIGNEE",
              reference,
              userId,
              role,
              evidence,
              at));
    }
    return result.users().stream().distinct().toList();
  }

  private ObjectNode normalize(JsonNode configured, ObjectNode fallback) {
    if (configured != null && configured.isObject()) {
      return configured.deepCopy();
    }
    if (configured != null && configured.isTextual() && !configured.asText().isBlank()) {
      ObjectNode normalized = JsonNodeFactory.instance.objectNode();
      normalized.put("type", configured.asText());
      return normalized;
    }
    return fallback.deepCopy();
  }
}
