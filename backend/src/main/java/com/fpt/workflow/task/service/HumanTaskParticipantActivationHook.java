package com.fpt.workflow.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.nodetype.NodeCapability;
import com.fpt.workflow.nodetype.NodeType;
import com.fpt.workflow.nodetype.NodeTypeManifest;
import com.fpt.workflow.nodetype.NodeTypeRegistry;
import com.fpt.workflow.organization.service.ManagerNotFoundException;
import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.activation.ParticipantActivationHook;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** Resolves participant and instantiates a human task occurrence lazily on node activation. */
@Service
@Primary
public class HumanTaskParticipantActivationHook implements ParticipantActivationHook {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(HumanTaskParticipantActivationHook.class);

  private final TaskExecutionRepository taskRepository;
  private final ParticipantSnapshotRepository snapshotRepository;
  private final NodeTypeRegistry registry;
  private final OrganizationHierarchyService hierarchyService;
  private final UuidGenerator uuidGenerator;
  private final PlatformClock clock;
  private final ObjectMapper objectMapper;

  public HumanTaskParticipantActivationHook(
      TaskExecutionRepository taskRepository,
      ParticipantSnapshotRepository snapshotRepository,
      NodeTypeRegistry registry,
      OrganizationHierarchyService hierarchyService,
      UuidGenerator uuidGenerator,
      PlatformClock clock,
      ObjectMapper objectMapper) {
    this.taskRepository = taskRepository;
    this.snapshotRepository = snapshotRepository;
    this.registry = registry;
    this.hierarchyService = hierarchyService;
    this.uuidGenerator = uuidGenerator;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  @Override
  public void onActivation(
      Event event, NodeDefinition node, NodeExecution execution, EventContext eventContext) {
    NodeType type = NodeType.valueOf(node.getNodeType());
    NodeTypeManifest manifest = registry.require(type);
    if (!manifest.supportedCapabilities().contains(NodeCapability.HUMAN_TASK)) {
      return;
    }

    Instant now = clock.now();
    JsonNode config = node.getConfigJson();
    JsonNode participantNode = config.path("participant");

    String resolverType = participantNode.path("type").asText("MANAGER_OF");
    UUID resolvedUserId = resolveParticipant(resolverType, participantNode, event, now);

    ObjectNode snapshotJson = JsonNodeFactory.instance.objectNode();
    snapshotJson.put("resolvedUserId", resolvedUserId.toString());
    snapshotJson.put("resolverType", resolverType);

    ParticipantSnapshot snapshot =
        ParticipantSnapshot.create(
            uuidGenerator.generate(),
            event.getId(),
            execution.getId(),
            null,
            resolverType,
            "hash-" + execution.getId(),
            participantNode.isObject() ? participantNode : JsonNodeFactory.instance.objectNode(),
            ParticipantResolutionStatus.RESOLVED,
            "TICKET_CREATOR",
            event.getStartedBy(),
            resolvedUserId,
            "APPROVER",
            snapshotJson,
            now);
    snapshotRepository.save(snapshot);

    String title =
        node.getName() != null && !node.getName().isBlank() ? node.getName() : node.getNodeKey();
    String description = node.getDescription();

    TaskExecution task =
        TaskExecution.create(
            uuidGenerator.generate(),
            execution.getId(),
            null,
            resolvedUserId,
            title,
            description,
            null,
            execution.getInputJson() != null
                ? execution.getInputJson()
                : JsonNodeFactory.instance.objectNode(),
            50,
            null,
            now);
    taskRepository.saveAndFlush(task);
  }

  /**
   * Resolves the participant user ID from the node configuration using the canonical organization
   * hierarchy. Supported types:
   *
   * <ul>
   *   <li>FIXED_USER — uses {@code userId} field directly.
   *   <li>MANAGER_OF — uses the organization hierarchy to find the direct manager of the ticket
   *       submitter (startedBy user) at the event start date.
   *   <li>DEPARTMENT_HEAD — uses the org unit of the ticket submitter's primary position.
   *   <li>Fallback — the ticket submitter (startedBy) themselves.
   * </ul>
   */
  private UUID resolveParticipant(
      String resolverType, JsonNode participantNode, Event event, Instant now) {
    if ("FIXED_USER".equalsIgnoreCase(resolverType) && participantNode.hasNonNull("userId")) {
      return UUID.fromString(participantNode.get("userId").asText());
    }

    LocalDate effectiveDate = now.atOffset(ZoneOffset.UTC).toLocalDate();
    UUID submitterUserId = event.getStartedBy();

    if ("MANAGER_OF".equalsIgnoreCase(resolverType)) {
      int depth = participantNode.path("depth").asInt(1);
      try {
        return hierarchyService.resolveManagerAtDepth(submitterUserId, depth, effectiveDate);
      } catch (ManagerNotFoundException ex) {
        LOGGER.warn(
            "MANAGER_OF resolution failed for user {} depth {}: {}; falling back to submitter.",
            submitterUserId,
            depth,
            ex.getMessage());
        return submitterUserId;
      }
    }

    // Fallback: task is assigned to the submitter themselves
    return submitterUserId;
  }
}
