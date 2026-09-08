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
}
