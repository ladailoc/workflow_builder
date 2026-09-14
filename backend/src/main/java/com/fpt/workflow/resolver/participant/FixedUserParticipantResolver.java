package com.fpt.workflow.resolver.participant;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class FixedUserParticipantResolver implements ParticipantResolver {
  public String type() {
    return "FIXED_USER";
  }

  public Set<String> configProperties() {
    return Set.of("type", "userId");
  }

  public UUID resolve(ParticipantResolverContext context) {
    if (!context.config().hasNonNull("userId")) {
      throw new IllegalArgumentException("FIXED_USER requires userId");
    }
    return UUID.fromString(context.config().path("userId").asText());
  }

  @Override
  public com.fpt.workflow.resolver.domain.ParticipantResolutionResult resolveResult(
      ParticipantResolverContext context) {
    com.fasterxml.jackson.databind.JsonNode config = context.config();
    java.util.List<UUID> users = new java.util.ArrayList<>();
    if (config.hasNonNull("userId")) {
      try {
        users.add(UUID.fromString(config.path("userId").asText()));
      } catch (IllegalArgumentException ignored) {
      }
    }
    if (config.path("users").isArray()) {
      for (com.fasterxml.jackson.databind.JsonNode u : config.path("users")) {
        try {
          users.add(UUID.fromString(u.asText()));
        } catch (IllegalArgumentException ignored) {
        }
      }
    }
    if (config.path("userIds").isArray()) {
      for (com.fasterxml.jackson.databind.JsonNode u : config.path("userIds")) {
        try {
          users.add(UUID.fromString(u.asText()));
        } catch (IllegalArgumentException ignored) {
        }
      }
    }
    if (users.isEmpty()) {
      return com.fpt.workflow.resolver.domain.ParticipantResolutionResult.notFound(
          "FIXED_USER requires userId or users array", type());
    }
    return com.fpt.workflow.resolver.domain.ParticipantResolutionResult.resolved(users, type());
  }
}
