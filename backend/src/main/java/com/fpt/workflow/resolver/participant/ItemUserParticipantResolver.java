package com.fpt.workflow.resolver.participant;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class ItemUserParticipantResolver implements ParticipantResolver {
  public String type() {
    return "ITEM_USER";
  }

  public Set<String> configProperties() {
    return Set.of("type");
  }

  public UUID resolve(ParticipantResolverContext context) {
    return ParticipantItemIds.requiredUserId(context.item());
  }
}
