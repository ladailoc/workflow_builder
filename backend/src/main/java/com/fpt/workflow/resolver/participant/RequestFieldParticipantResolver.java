package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves participants from a specified field in ticket payload per workflow_spec.md §9.2.
 * Supports single USER (UUID string) or USER_LIST (array of UUID strings).
 */
@Component
public final class RequestFieldParticipantResolver implements ParticipantResolver {

  public static final String TYPE = "REQUEST_FIELD";
  private final EmployeeRepository employees;

  @Autowired
  public RequestFieldParticipantResolver(EmployeeRepository employees) {
    this.employees = employees;
  }

  public RequestFieldParticipantResolver() {
    this.employees = null;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public Set<String> configProperties() {
    return Set.of("type", "field", "path", "fieldType");
  }

  @Override
  public UUID resolve(ParticipantResolverContext context) {
    List<UUID> users = extractUsers(context);
    if (users.isEmpty()) {
      throw new IllegalArgumentException("No valid user found in ticket field: " + getFieldPath(context.config()));
    }
    return users.getFirst();
  }

  @Override
  public ParticipantResolutionResult resolveResult(ParticipantResolverContext context) {
    try {
      List<UUID> users = extractUsers(context);
      if (users.isEmpty()) {
        return ParticipantResolutionResult.notFound(
            "Ticket field " + getFieldPath(context.config()) + " has no resolved users", TYPE);
      }
      if (employees != null) {
        for (UUID userId : users) {
          Optional<Employee> employee = employees.findByUserId(userId);
          if (employee.isEmpty()) {
            return ParticipantResolutionResult.notFound(
                "Ticket field references a user outside the organization directory: " + userId,
                TYPE);
          }
          if (!employee.get().isActive()) {
            return ParticipantResolutionResult.inactive(
                userId, "Ticket field references an inactive user: " + userId, TYPE);
          }
        }
      }
      return ParticipantResolutionResult.resolved(users, TYPE);
    } catch (Exception ex) {
      return ParticipantResolutionResult.failed(ex.getMessage(), TYPE);
    }
  }

  private List<UUID> extractUsers(ParticipantResolverContext context) {
    String fieldPath = getFieldPath(context.config());
    if (fieldPath == null || fieldPath.isBlank()) {
      return List.of();
    }

    JsonNode node = resolveNodePath(context.ticketData(), fieldPath);
    if (node == null || node.isMissingNode() || node.isNull()) {
      return List.of();
    }

    String declaredType =
        context.config().path("fieldType").asText("").trim().toUpperCase(Locale.ROOT);
    if (!declaredType.isEmpty()
        && !"USER".equals(declaredType)
        && !"USER_LIST".equals(declaredType)) {
      throw new IllegalArgumentException(
          "REQUEST_FIELD requires a USER or USER_LIST field, not " + declaredType);
    }
    if ("USER".equals(declaredType) && node.isArray()) {
      throw new IllegalArgumentException("USER field cannot contain an array");
    }
    if ("USER_LIST".equals(declaredType) && !node.isArray()) {
      throw new IllegalArgumentException("USER_LIST field must contain an array");
    }

    LinkedHashSet<UUID> users = new LinkedHashSet<>();
    if (node.isTextual()) {
      users.add(parseUserId(node));
    } else if (node.isArray()) {
      for (JsonNode item : node) {
        users.add(parseUserId(item));
      }
    } else {
      throw new IllegalArgumentException("REQUEST_FIELD value must be a USER or USER_LIST");
    }
    return List.copyOf(users);
  }

  private UUID parseUserId(JsonNode value) {
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("REQUEST_FIELD contains a non-USER value");
    }
    try {
      return UUID.fromString(value.asText().trim());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "REQUEST_FIELD contains an invalid USER identifier", exception);
    }
  }

  private String getFieldPath(JsonNode config) {
    if (config.hasNonNull("path")) {
      return config.path("path").asText();
    }
    return config.path("field").asText(null);
  }

  private JsonNode resolveNodePath(JsonNode root, String path) {
    if (root == null || path == null) return null;
    String[] parts = path.split("\\.");
    JsonNode current = root;
    for (String part : parts) {
      if (current == null || !current.has(part)) {
        return null;
      }
      current = current.get(part);
    }
    return current;
  }
}
