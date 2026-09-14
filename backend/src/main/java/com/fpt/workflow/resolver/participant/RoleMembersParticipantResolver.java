package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves all active users with a specified role per workflow_spec.md §9.2.
 */
@Component
public final class RoleMembersParticipantResolver implements ParticipantResolver {

  public static final String TYPE = "ROLE_MEMBERS";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public Set<String> configProperties() {
    return Set.of("type", "role", "roles", "candidateUserIds");
  }

  @Override
  public UUID resolve(ParticipantResolverContext context) {
    List<UUID> users = extractRoleMembers(context.config());
    if (users.isEmpty()) {
      throw new IllegalArgumentException("No role members resolved for " + context.config().path("role").asText());
    }
    return users.getFirst();
  }

  @Override
  public ParticipantResolutionResult resolveResult(ParticipantResolverContext context) {
    List<UUID> users = extractRoleMembers(context.config());
    if (users.isEmpty()) {
      return ParticipantResolutionResult.notFound(
          "No active members found for role: " + context.config().path("role").asText(), TYPE);
    }
    return ParticipantResolutionResult.resolved(users, TYPE);
  }

  private List<UUID> extractRoleMembers(JsonNode config) {
    List<UUID> users = new ArrayList<>();
    JsonNode candidateUsers = config.path("candidateUserIds");
    if (candidateUsers.isArray()) {
      for (JsonNode item : candidateUsers) {
        if (item.isTextual()) {
          try {
            users.add(UUID.fromString(item.asText().trim()));
          } catch (IllegalArgumentException ignored) {
          }
        }
      }
    }
    return List.copyOf(users);
  }
}
