package com.fpt.workflow.resolver.participant;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class CreatorParticipantResolver implements ParticipantResolver {
  public String type() {
    return "CREATOR";
  }

  public Set<String> configProperties() {
    return Set.of("type");
  }

  public UUID resolve(ParticipantResolverContext context) {
    return context.creatorId();
  }
}
