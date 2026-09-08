package com.fpt.workflow.resolver.participant;

import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class ItemManagerParticipantResolver implements ParticipantResolver {
  private final OrganizationHierarchyService hierarchy;

  public ItemManagerParticipantResolver(OrganizationHierarchyService hierarchy) {
    this.hierarchy = hierarchy;
  }

  public String type() {
    return "ITEM_MANAGER";
  }

  public Set<String> configProperties() {
    return Set.of("type", "depth");
  }

  public UUID resolve(ParticipantResolverContext context) {
    int depth = context.config().path("depth").asInt(1);
    if (depth < 1) throw new IllegalArgumentException("ITEM_MANAGER depth must be positive");
    return hierarchy.resolveManagerAtDepth(
        ParticipantItemIds.requiredUserId(context.item()),
        depth,
        context.resolvedAt().atOffset(ZoneOffset.UTC).toLocalDate());
  }
}
