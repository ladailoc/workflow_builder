package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes dynamic participant resolution and explicit fallback chains per workflow_spec.md §9.3 & §9.4.
 *
 * <p>Fallback chain evaluation order:
 * Primary -> Fallback 1 -> Fallback 2 -> OnMissing (CREATE_MANUAL_TASK | FAIL_NODE)
 */
@Service
public class ParticipantResolutionEngine {

  private static final Logger log = LoggerFactory.getLogger(ParticipantResolutionEngine.class);

  public enum OnMissingPolicy {
    CREATE_MANUAL_TASK,
    FAIL_NODE;

    public static OnMissingPolicy fromText(String text) {
      if (text != null && text.equalsIgnoreCase("CREATE_MANUAL_TASK")) {
        return CREATE_MANUAL_TASK;
      }
      return FAIL_NODE;
    }
  }

  public record ResolutionOutcome(
      ParticipantResolutionResult result,
      OnMissingPolicy onMissingPolicy,
      String appliedStage,
      List<ParticipantResolutionResult> evaluationTrace) {}

  private final ParticipantResolverRegistry resolverRegistry;
  private final EmployeeRepository employeeRepository;

  public ParticipantResolutionEngine(
      ParticipantResolverRegistry resolverRegistry,
      EmployeeRepository employeeRepository) {
    this.resolverRegistry = resolverRegistry;
    this.employeeRepository = employeeRepository;
  }

  /**
   * Resolves participants evaluating primary config, fallback chain, subject resolution, and inactive detection.
   */
  public ResolutionOutcome resolve(
      JsonNode nodeConfig,
      ParticipantResolverContext rawContext,
      UUID workflowOwnerId) {
    Objects.requireNonNull(nodeConfig, "nodeConfig");
    Objects.requireNonNull(rawContext, "rawContext");

    JsonNode participantNode = nodeConfig.path("participant");
    OnMissingPolicy onMissingPolicy =
        OnMissingPolicy.fromText(participantNode.path("onMissing").asText("FAIL_NODE"));

    List<ParticipantResolutionResult> trace = new ArrayList<>();

    // 1. Evaluate Primary Resolver
    ObjectNode primaryConfig;
    if (participantNode.has("resolver") && participantNode.get("resolver").isObject()) {
      primaryConfig = participantNode.get("resolver").deepCopy();
    } else if (participantNode.isObject()) {
      primaryConfig = participantNode.deepCopy();
    } else {
      primaryConfig = JsonNodeFactory.instance.objectNode();
    }

    if (!primaryConfig.hasNonNull("type")) {
      if (primaryConfig.hasNonNull("sourceType")) {
        primaryConfig.put("type", primaryConfig.path("sourceType").asText());
      } else if (participantNode.hasNonNull("sourceType")) {
        primaryConfig.put("type", participantNode.path("sourceType").asText());
      } else if (participantNode.has("users")
          || participantNode.has("userId")
          || participantNode.has("userIds")
          || participantNode.has("assignees")) {
        primaryConfig.put("type", "FIXED_USER");
        if (participantNode.has("users") && !primaryConfig.has("users")) {
          primaryConfig.set("users", participantNode.get("users"));
        }
        if (participantNode.has("userId") && !primaryConfig.has("userId")) {
          primaryConfig.set("userId", participantNode.get("userId"));
        }
        if (participantNode.has("userIds") && !primaryConfig.has("userIds")) {
          primaryConfig.set("userIds", participantNode.get("userIds"));
        }
        if (participantNode.has("assignees") && !primaryConfig.has("users")) {
          primaryConfig.set("users", participantNode.get("assignees"));
        }
      } else {
        primaryConfig.put("type", "CREATOR");
      }
    }
    String primaryType = primaryConfig.path("type").asText();
    SubjectResolution primarySubject =
        resolveSubject(primaryConfig, participantNode, rawContext);

    ParticipantResolverContext primaryContext =
        new ParticipantResolverContext(
            rawContext.creatorId(),
            rawContext.referenceUserId(),
            rawContext.item(),
            primaryConfig,
            rawContext.resolvedAt(),
            rawContext.ticketData(),
            rawContext.executionData(),
            primarySubject.userId(),
            rawContext.ticketSubjects());

    ParticipantResolutionResult primaryResult =
        primarySubject
            .failure(primaryType)
            .orElseGet(() -> executeAndCheckActive(primaryType, primaryContext));
    trace.add(primaryResult);

    if (primaryResult.isResolved()) {
      return new ResolutionOutcome(primaryResult, onMissingPolicy, "PRIMARY", trace);
    }

    log.warn(
        "Primary participant resolution ({}) did not resolve: status={}, reason={}. Evaluating fallback chain.",
        primaryType,
        primaryResult.status(),
        primaryResult.reason());

    // 2. Evaluate Fallback Chain (P1-03)
    List<JsonNode> fallbackConfigs = extractFallbackChain(participantNode);
    for (int i = 0; i < fallbackConfigs.size(); i++) {
      JsonNode fbConfig = fallbackConfigs.get(i);
      String fbType = fbConfig.path("type").asText();
      SubjectResolution fallbackSubject = resolveSubject(fbConfig, participantNode, rawContext);

      ParticipantResolverContext fbContext =
          new ParticipantResolverContext(
              rawContext.creatorId(),
              rawContext.referenceUserId(),
              rawContext.item(),
              fbConfig,
              rawContext.resolvedAt(),
              rawContext.ticketData(),
              rawContext.executionData(),
              fallbackSubject.userId(),
              rawContext.ticketSubjects());

      ParticipantResolutionResult fbResult;
      Optional<ParticipantResolutionResult> subjectFailure = fallbackSubject.failure(fbType);
      if (subjectFailure.isPresent()) {
        fbResult = subjectFailure.get();
      } else if ("WORKFLOW_OWNER".equalsIgnoreCase(fbType)) {
        fbResult =
            workflowOwnerId != null
                ? ParticipantResolutionResult.resolved(workflowOwnerId, "WORKFLOW_OWNER")
                : ParticipantResolutionResult.notFound("Workflow owner ID is null", "WORKFLOW_OWNER");
      } else {
        fbResult = executeAndCheckActive(fbType, fbContext);
      }
      trace.add(fbResult);

      if (fbResult.isResolved()) {
        log.info("Fallback stage {} ({}) successfully resolved participant(s): {}", i + 1, fbType, fbResult.users());
        return new ResolutionOutcome(fbResult, onMissingPolicy, "FALLBACK_" + (i + 1), trace);
      }
    }

    // 3. Exhausted chain -> return final non-resolved result with OnMissing policy
    ParticipantResolutionResult finalResult =
        trace.isEmpty() ? primaryResult : trace.getLast();
    return new ResolutionOutcome(finalResult, onMissingPolicy, "EXHAUSTED", trace);
  }

  private ParticipantResolutionResult executeAndCheckActive(
      String type, ParticipantResolverContext context) {
    ParticipantResolutionResult rawResult;
    if (resolverRegistry != null && resolverRegistry.hasResolver(type)) {
      rawResult = resolverRegistry.resolveResult(type, context);
    } else if ("FIXED_USER".equalsIgnoreCase(type)) {
      rawResult = new FixedUserParticipantResolver().resolveResult(context);
    } else if ("CREATOR".equalsIgnoreCase(type)) {
      rawResult = new CreatorParticipantResolver().resolveResult(context);
    } else {
      return ParticipantResolutionResult.failed("Resolver type not registered: " + type, type);
    }

    if (!rawResult.isResolved()) {
      return rawResult;
    }

    // P1-07: Inactive Assignee Detection
    List<UUID> activeUsers = new ArrayList<>();
    List<UUID> inactiveUsers = new ArrayList<>();
    if (employeeRepository != null) {
      for (UUID uid : rawResult.users()) {
        Optional<Employee> emp = employeeRepository.findByUserId(uid);
        if (emp.isPresent() && !emp.get().isActive()) {
          inactiveUsers.add(uid);
        } else {
          activeUsers.add(uid);
        }
      }
    } else {
      activeUsers.addAll(rawResult.users());
    }

    if (activeUsers.isEmpty() && !inactiveUsers.isEmpty()) {
      return ParticipantResolutionResult.inactive(
          inactiveUsers.getFirst(),
          "Resolved user " + inactiveUsers.getFirst() + " is inactive",
          type);
    }

    return ParticipantResolutionResult.resolved(activeUsers, type);
  }

  private SubjectResolution resolveSubject(
      JsonNode resolverConfig, JsonNode participantNode, ParticipantResolverContext context) {
    JsonNode subjectNode = resolverConfig.path("subject");
    if (subjectNode.isMissingNode() || subjectNode.isNull()) {
      subjectNode = participantNode.path("subject");
    }
    String subjectSource = "TICKET_CREATOR";
    if (subjectNode.isObject() && subjectNode.hasNonNull("type")) {
      subjectSource = subjectNode.get("type").asText();
    } else if (subjectNode.isTextual() && !subjectNode.asText().isBlank()) {
      subjectSource = subjectNode.asText();
    }
    subjectSource = subjectSource.toUpperCase(java.util.Locale.ROOT);

    return switch (subjectSource) {
      case "CURRENT_ITEM" -> {
        if (context.item() != null && !context.item().isNull()) {
          yield extractUserId(context.item())
              .map(SubjectResolution::resolved)
              .orElseGet(() -> SubjectResolution.notFound("Current item is not a USER subject"));
        }
        yield SubjectResolution.notFound("Current item is not available");
      }
      case "REQUEST_FIELD" -> {
        String field =
            resolverConfig.path("subjectField").asText(
                resolverConfig.path("field").asText(
                    participantNode.path("subjectField").asText(
                        participantNode.path("field").asText(null))));
        JsonNode value = resolvePath(context.ticketData(), field);
        if (value != null) {
          List<UUID> users = extractUserIds(value);
          if (users.size() == 1) yield SubjectResolution.resolved(users.getFirst());
          if (users.size() > 1) {
            yield SubjectResolution.ambiguous(
                users, "Request field resolved multiple business subjects");
          }
        }
        yield SubjectResolution.notFound("Request-field subject was not found: " + field);
      }
      case "REQUEST_SUBJECT", "TICKET_SUBJECT", "BUSINESS_SUBJECT" ->
          resolveTicketSubject(subjectNode, resolverConfig, participantNode, context.ticketSubjects());
      case "PREVIOUS_PARTICIPANT" -> SubjectResolution.resolved(context.referenceUserId());
      case "TICKET_CREATOR", "CREATOR" -> SubjectResolution.resolved(context.creatorId());
      default -> {
        if (subjectSource.startsWith("${") && subjectSource.endsWith("}")) {
          yield extractUserId(context.item())
              .map(SubjectResolution::resolved)
              .orElseGet(
                  () -> SubjectResolution.notFound("Subject expression did not resolve a USER"));
        }
        yield SubjectResolution.notFound(
            "Unsupported participant subject selector: " + subjectSource);
      }
    };
  }

  private SubjectResolution resolveTicketSubject(
      JsonNode subjectNode,
      JsonNode resolverConfig,
      JsonNode participantNode,
      JsonNode subjects) {
    String role =
        subjectNode.isObject()
            ? subjectNode.path("role").asText(subjectNode.path("roleKey").asText(null))
            : null;
    if (role == null) {
      role =
          resolverConfig
              .path("subjectRole")
              .asText(participantNode.path("subjectRole").asText(null));
    }
    String type = subjectNode.isObject() ? subjectNode.path("subjectType").asText(null) : null;
    List<UUID> matches = new ArrayList<>();
    if (subjects != null && subjects.isArray()) {
      for (JsonNode subject : subjects) {
        if (role != null && !role.equalsIgnoreCase(subject.path("role").asText())) continue;
        if (type != null && !type.equalsIgnoreCase(subject.path("type").asText())) continue;
        extractUserId(subject.path("referenceId")).ifPresent(matches::add);
      }
    }
    List<UUID> distinct = matches.stream().distinct().toList();
    if (distinct.isEmpty()) {
      return SubjectResolution.notFound("Configured Ticket subject was not found");
    }
    if (distinct.size() > 1) {
      return SubjectResolution.ambiguous(distinct, "Configured Ticket subject is ambiguous");
    }
    return SubjectResolution.resolved(distinct.getFirst());
  }

  private JsonNode resolvePath(JsonNode root, String path) {
    if (root == null || path == null || path.isBlank()) return null;
    JsonNode current = root;
    for (String part : path.split("\\.")) {
      if (current == null || !current.has(part)) return null;
      current = current.get(part);
    }
    return current;
  }

  private List<UUID> extractUserIds(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) return List.of();
    if (!node.isArray()) return extractUserId(node).map(List::of).orElseGet(List::of);
    List<UUID> users = new ArrayList<>();
    node.forEach(value -> extractUserId(value).ifPresent(users::add));
    return users.stream().distinct().toList();
  }

  private Optional<UUID> extractUserId(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) return Optional.empty();
    String text = node.isTextual() ? node.asText() : node.path("id").asText(node.path("userId").asText(null));
    if (text == null || text.isBlank()) return Optional.empty();
    try {
      return Optional.of(UUID.fromString(text.trim()));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  private record SubjectResolution(
      ParticipantResolutionStatus status, UUID userId, List<UUID> candidates, String reason) {
    static SubjectResolution resolved(UUID userId) {
      return new SubjectResolution(
          ParticipantResolutionStatus.RESOLVED, userId, List.of(userId), null);
    }

    static SubjectResolution notFound(String reason) {
      return new SubjectResolution(
          ParticipantResolutionStatus.NOT_FOUND, null, List.of(), reason);
    }

    static SubjectResolution ambiguous(List<UUID> candidates, String reason) {
      return new SubjectResolution(
          ParticipantResolutionStatus.AMBIGUOUS, null, List.copyOf(candidates), reason);
    }

    Optional<ParticipantResolutionResult> failure(String resolverType) {
      if (status == ParticipantResolutionStatus.RESOLVED) return Optional.empty();
      if (status == ParticipantResolutionStatus.AMBIGUOUS) {
        return Optional.of(
            ParticipantResolutionResult.ambiguous(candidates, reason, resolverType));
      }
      return Optional.of(ParticipantResolutionResult.notFound(reason, resolverType));
    }
  }

  private List<JsonNode> extractFallbackChain(JsonNode participantNode) {
    List<JsonNode> chain = new ArrayList<>();

    // Fallbacks are business policy and therefore only come from immutable workflow config.
    JsonNode explicitFallbacks = participantNode.path("fallbackChain");
    if (!explicitFallbacks.isArray()) {
      explicitFallbacks = participantNode.path("fallbacks");
    }
    if (!explicitFallbacks.isArray()) {
      explicitFallbacks = participantNode.path("fallback");
    }
    if (explicitFallbacks.isArray()) {
      for (JsonNode fb : explicitFallbacks) {
        if (fb.isObject()) {
          ObjectNode fbObj = fb.deepCopy();
          if (!fbObj.hasNonNull("type") && fbObj.hasNonNull("sourceType")) {
            fbObj.put("type", fbObj.get("sourceType").asText());
          }
          if (fbObj.hasNonNull("type")) {
            chain.add(fbObj);
          }
        }
      }
    }
    return chain;
  }
}
