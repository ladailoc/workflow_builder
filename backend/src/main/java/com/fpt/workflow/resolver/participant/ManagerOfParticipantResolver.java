package com.fpt.workflow.resolver.participant;

import com.fpt.workflow.organization.service.ManagerNotFoundException;
import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class ManagerOfParticipantResolver implements ParticipantResolver {
  private static final Logger LOGGER = LoggerFactory.getLogger(ManagerOfParticipantResolver.class);
  private final OrganizationHierarchyService hierarchy;

  public ManagerOfParticipantResolver(OrganizationHierarchyService hierarchy) {
    this.hierarchy = hierarchy;
  }

  public String type() {
    return "MANAGER_OF";
  }

  public Set<String> configProperties() {
    return Set.of("type", "depth");
  }

  public UUID resolve(ParticipantResolverContext context) {
    int depth = context.config().path("depth").asInt(1);
    if (depth < 1) throw new IllegalArgumentException("MANAGER_OF depth must be positive");
    try {
      return hierarchy.resolveManagerAtDepth(
          context.referenceUserId(),
          depth,
          context.resolvedAt().atOffset(ZoneOffset.UTC).toLocalDate());
    } catch (ManagerNotFoundException exception) {
      LOGGER.warn(
          "MANAGER_OF resolution failed for user {} depth {}; using explicit creator fallback",
          context.referenceUserId(),
          depth);
      return context.creatorId();
    }
  }
}
