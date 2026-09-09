package com.fpt.workflow.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.participant.ParticipantResolverContext;
import com.fpt.workflow.resolver.participant.ParticipantResolverRegistry;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.domain.RuntimeWaitReason;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.domain.lifecycle.EventStatus;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a participant-resolved manual recovery task only when the published node allows it. */
@Service
public class ManualRecoveryTaskService {

  private static final Set<EventStatus> TERMINAL_EVENTS =
      EnumSet.of(
          EventStatus.COMPLETED, EventStatus.FAILED, EventStatus.CANCELLED, EventStatus.TERMINATED);
  private static final Set<TaskStatus> TERMINAL_TASKS =
      EnumSet.of(TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.EXPIRED);

  private final IntegrationExecutionRepository integrations;
  private final EventRepository events;
  private final NodeExecutionRepository executions;
  private final NodeDefinitionRepository nodes;
  private final TaskExecutionRepository tasks;
  private final ParticipantSnapshotRepository snapshots;
  private final ParticipantResolverRegistry resolvers;
  private final CanonicalDefinitionJson canonicalJson;
  private final UuidGenerator uuids;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;

  public ManualRecoveryTaskService(
      IntegrationExecutionRepository integrations,
      EventRepository events,
      NodeExecutionRepository executions,
      NodeDefinitionRepository nodes,
      TaskExecutionRepository tasks,
      ParticipantSnapshotRepository snapshots,
      ParticipantResolverRegistry resolvers,
      CanonicalDefinitionJson canonicalJson,
      UuidGenerator uuids,
      PlatformClock clock,
      ObjectMapper objectMapper) {
    this.integrations = integrations;
    this.events = events;
    this.executions = executions;
    this.nodes = nodes;
    this.tasks = tasks;
    this.snapshots = snapshots;
    this.resolvers = resolvers;
    this.canonicalJson = canonicalJson;
    this.uuids = uuids;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public TaskExecution create(UUID integrationExecutionId) {
    IntegrationExecution integration =
        integrations
            .findByIdForUpdate(integrationExecutionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "IntegrationExecution not found: " + integrationExecutionId));
    if (integration.getStatus() != IntegrationExecutionStatus.MANUAL_RECONCILIATION) {
      throw new CommandConflictException(
          "MANUAL_RECOVERY_NOT_REQUIRED", "Integration is not awaiting manual reconciliation");
    }
    Event event = events.findByIdForUpdate(integration.getEventId()).orElseThrow();
    if (TERMINAL_EVENTS.contains(event.getStatus())) {
      throw new CommandConflictException(
          "TERMINAL_EVENT_WINS", "A terminal Event cannot receive a recovery task");
    }
    NodeExecution execution =
        executions.findByIdForUpdate(integration.getNodeExecutionId()).orElseThrow();
    if (execution.getStatus() != NodeExecutionStatus.WAITING
        || execution.getWaitReason() != RuntimeWaitReason.MANUAL_RECONCILIATION) {
      throw new CommandConflictException(
          "RECOVERY_NODE_NOT_WAITING", "Integration node is not awaiting manual recovery");
    }
    tasks.findAllByNodeExecutionIdOrderByCreatedAtAsc(execution.getId()).stream()
        .filter(task -> !TERMINAL_TASKS.contains(task.getStatus()))
        .findFirst()
        .ifPresent(
            task -> {
              throw new CommandConflictException(
                  "MANUAL_RECOVERY_TASK_EXISTS", "An active recovery task already exists");
            });

    NodeDefinition node = nodes.findById(execution.getNodeDefinitionId()).orElseThrow();
    JsonNode failure = node.getConfigJson().path("failure");
    if (!failure.isObject()
        || !"CREATE_MANUAL_TASK".equals(failure.path("afterRetryExhausted").asText())) {
      throw new CommandConflictException(
          "MANUAL_RECOVERY_NOT_CONFIGURED",
          "Published node configuration does not permit a manual recovery task");
    }
    JsonNode taskConfig = failure.path("manualTask");
    JsonNode participant = taskConfig.path("participant");
    if (!taskConfig.isObject() || !participant.isObject()) {
      throw new IllegalStateException("Manual recovery task participant configuration is missing");
    }
    var validationIssues = resolvers.validate(participant);
    if (!validationIssues.isEmpty()) {
      throw new IllegalStateException(validationIssues.getFirst().message());
    }
    Instant now = clock.now();
    UUID assignee =
        resolvers.resolve(
            participant.path("type").asText(),
            new ParticipantResolverContext(
                event.getStartedBy(), event.getStartedBy(), null, participant, now));
    String configHash = canonicalJson.checksum(canonicalJson.canonicalize(participant));
    ObjectNode snapshotJson = objectMapper.createObjectNode();
    snapshotJson.put("resolvedUserId", assignee.toString());
    snapshotJson.put("resolverType", participant.path("type").asText());
    snapshots.save(
        ParticipantSnapshot.create(
            uuids.generate(),
            event.getId(),
            execution.getId(),
            null,
            participant.path("type").asText(),
            configHash,
            participant,
            ParticipantResolutionStatus.RESOLVED,
            "TICKET_CREATOR",
            event.getStartedBy(),
            assignee,
            "MANUAL_RECOVERY",
            snapshotJson,
            now));
    return tasks.saveAndFlush(
        TaskExecution.create(
            uuids.generate(),
            execution.getId(),
            null,
            assignee,
            taskConfig.path("title").asText("Manual integration recovery"),
            taskConfig.path("description").asText(null),
            null,
            execution.getInputJson() == null
                ? JsonNodeFactory.instance.objectNode()
                : execution.getInputJson(),
            taskConfig.path("priority").asInt(90),
            null,
            now));
  }
}
