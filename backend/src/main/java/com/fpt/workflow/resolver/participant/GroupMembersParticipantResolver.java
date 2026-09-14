package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves all active members of an organizational group or pool per workflow_spec.md §9.2.
 */
@Component
public final class GroupMembersParticipantResolver implements ParticipantResolver {

  public static final String TYPE = "GROUP_MEMBERS";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public Set<String> configProperties() {
    return Set.of("type", "groupId", "groupKey", "candidateUserIds");
  }

  @Override
  public UUID resolve(ParticipantResolverContext context) {
    List<UUID> users = extractMembers(context.config());
    if (users.isEmpty()) {
      throw new IllegalArgumentException(
          "No group members resolved for "
              + context.config().path("groupKey").asText(context.config().path("groupId").asText()));
    }
    return users.getFirst();
  }

  @Override
  public ParticipantResolutionResult resolveResult(ParticipantResolverContext context) {
    List<UUID> users = extractMembers(context.config());
    if (users.isEmpty()) {
      return ParticipantResolutionResult.notFound(
          "No active members found for group: "
              + context.config().path("groupKey").asText(context.config().path("groupId").asText()),
          TYPE);
    }
    return ParticipantResolutionResult.resolved(users, TYPE);
  }

  private List<UUID> extractMembers(JsonNode config) {
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
