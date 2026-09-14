package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves participant user(s) via safe expression or property reference per workflow_spec.md §9.2.
 */
@Component
public final class ExpressionParticipantResolver implements ParticipantResolver {

  public static final String TYPE = "EXPRESSION";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public Set<String> configProperties() {
    return Set.of("type", "expression", "expr", "path", "value");
  }

  @Override
  public UUID resolve(ParticipantResolverContext context) {
    ParticipantResolutionResult result = resolveResult(context);
    return result.singleUser()
        .orElseThrow(
            () -> new IllegalArgumentException("EXPRESSION resolution failed: " + result.reason()));
  }

  @Override
  public ParticipantResolutionResult resolveResult(ParticipantResolverContext context) {
    String expr =
        context.config().path("expression").asText(
            context.config().path("expr").asText(
                context.config().path("path").asText(
                    context.config().path("value").asText(null))));

    if (expr == null || expr.isBlank()) {
      return ParticipantResolutionResult.failed("Expression is blank", TYPE);
    }

    expr = expr.trim();
    if (expr.startsWith("${") && expr.endsWith("}")) {
      expr = expr.substring(2, expr.length() - 1).trim();
    }

    // Resolve from item, ticketData, or executionData
    JsonNode target = null;
    if (context.item() != null && !context.item().isNull() && !context.item().isMissingNode()) {
      target = resolvePath(context.item(), expr);
    }
    if (target == null || target.isMissingNode() || target.isNull()) {
      target = resolvePath(context.ticketData(), expr);
    }
    if (target == null || target.isMissingNode() || target.isNull()) {
      target = resolvePath(context.executionData(), expr);
    }

    if (target == null || target.isMissingNode() || target.isNull()) {
      return ParticipantResolutionResult.notFound("Expression evaluated to null or missing: " + expr, TYPE);
    }

    if (target.isTextual()) {
      try {
        UUID user = UUID.fromString(target.asText().trim());
        return ParticipantResolutionResult.resolved(user, TYPE);
      } catch (IllegalArgumentException ex) {
        return ParticipantResolutionResult.failed(
            "Expression result '" + target.asText() + "' is not a valid UUID", TYPE);
      }
    } else if (target.isArray()) {
      List<UUID> users = new ArrayList<>();
      for (JsonNode item : target) {
        if (item.isTextual()) {
          try {
            users.add(UUID.fromString(item.asText().trim()));
          } catch (IllegalArgumentException ignored) {
          }
        }
      }
      if (users.isEmpty()) {
        return ParticipantResolutionResult.notFound(
            "Expression array result contains no valid UUIDs", TYPE);
      }
      return ParticipantResolutionResult.resolved(users, TYPE);
    }

    return ParticipantResolutionResult.failed(
        "Expression result must be string or array of strings", TYPE);
  }

  private JsonNode resolvePath(JsonNode root, String path) {
    if (root == null || path == null) return null;
    String cleanPath = path.startsWith("$.") ? path.substring(2) : path.startsWith(".") ? path.substring(1) : path;

    // 1. Direct path lookup on root
    JsonNode target = traverseSegments(root, cleanPath);
    if (target != null && !target.isMissingNode() && !target.isNull()) {
      return target;
    }

    // 2. Strip leading "item." or "ticket.data." or "ticket." or "data."
    if (cleanPath.startsWith("item.")) {
      target = traverseSegments(root, cleanPath.substring(5));
      if (target != null && !target.isMissingNode() && !target.isNull()) {
        return target;
      }
    }
    if (cleanPath.startsWith("ticket.data.")) {
      target = traverseSegments(root, cleanPath.substring(12));
      if (target != null && !target.isMissingNode() && !target.isNull()) {
        return target;
      }
    }
    if (cleanPath.startsWith("ticket.")) {
      target = traverseSegments(root, cleanPath.substring(7));
      if (target != null && !target.isMissingNode() && !target.isNull()) {
        return target;
      }
    }
    if (cleanPath.startsWith("data.")) {
      target = traverseSegments(root, cleanPath.substring(5));
      if (target != null && !target.isMissingNode() && !target.isNull()) {
        return target;
      }
    }

    // 3. If path is a single segment (e.g. "employee" or "item"), and root is textual or has id/userId
    if (!cleanPath.contains(".")) {
      if (root.isTextual()) {
        return root;
      }
      if (root.isObject()) {
        if (root.hasNonNull(cleanPath)) {
          return root.get(cleanPath);
        }
        if (root.hasNonNull("id")) {
          return root.get("id");
        }
        if (root.hasNonNull("userId")) {
          return root.get("userId");
        }
        if (root.hasNonNull("employeeId")) {
          return root.get("employeeId");
        }
      }
    }

    // 4. Strip first segment if multi-instance variable name prefix (e.g. "employee.id" where root is employee object)
    if (cleanPath.contains(".")) {
      String strippedFirst = cleanPath.substring(cleanPath.indexOf('.') + 1);
      target = traverseSegments(root, strippedFirst);
      if (target != null && !target.isMissingNode() && !target.isNull()) {
        return target;
      }
    }

    return null;
  }

  private JsonNode traverseSegments(JsonNode root, String path) {
    if (root == null || path == null || path.isBlank()) return null;
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
