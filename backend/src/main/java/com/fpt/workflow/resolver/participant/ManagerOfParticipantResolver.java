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
    return Set.of("type", "depth", "subject");
  }

  public UUID resolve(ParticipantResolverContext context) {
    int depth = context.config().path("depth").asInt(1);
    if (depth < 1) throw new IllegalArgumentException("MANAGER_OF depth must be positive");
    UUID targetUserId = context.subjectUserId();
    if (targetUserId == null) {
      throw new IllegalArgumentException("MANAGER_OF subject did not resolve to a user");
    }
    return hierarchy.resolveManagerAtDepth(
        targetUserId,
        depth,
        context.resolvedAt().atOffset(ZoneOffset.UTC).toLocalDate());
  }

  @Override
  public com.fpt.workflow.resolver.domain.ParticipantResolutionResult resolveResult(
      ParticipantResolverContext context) {
    if (context.subjectUserId() == null) {
      return com.fpt.workflow.resolver.domain.ParticipantResolutionResult.notFound(
          "MANAGER_OF subject did not resolve to a user", type());
    }
    try {
      UUID managerId = resolve(context);
      return com.fpt.workflow.resolver.domain.ParticipantResolutionResult.resolved(managerId, type());
    } catch (ManagerNotFoundException ex) {
      return com.fpt.workflow.resolver.domain.ParticipantResolutionResult.vacant(ex.getMessage(), type());
    } catch (Exception ex) {
      return com.fpt.workflow.resolver.domain.ParticipantResolutionResult.failed(ex.getMessage(), type());
    }
  }
}
