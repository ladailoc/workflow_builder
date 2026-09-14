package com.fpt.workflow.resolver.participant;

import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the department head or unit lead per workflow_spec.md §9.2 / §9.3.
 */
@Component
public final class HeadOfUnitParticipantResolver implements ParticipantResolver {

  public static final String TYPE = "HEAD_OF_UNIT";
  private final OrganizationHierarchyService hierarchy;

  public HeadOfUnitParticipantResolver(OrganizationHierarchyService hierarchy) {
    this.hierarchy = hierarchy;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public Set<String> configProperties() {
    return Set.of("type", "unitType", "unitId", "subject");
  }

  @Override
  public UUID resolve(ParticipantResolverContext context) {
    ParticipantResolutionResult result = resolveResult(context);
    return result.singleUser()
        .orElseThrow(
            () -> new IllegalArgumentException("HEAD_OF_UNIT resolution failed: " + result.reason()));
  }

  @Override
  public ParticipantResolutionResult resolveResult(ParticipantResolverContext context) {
    LocalDate effectiveDate = context.resolvedAt().atOffset(ZoneOffset.UTC).toLocalDate();
    String unitIdStr = context.config().path("unitId").asText(null);
    if (unitIdStr != null && !unitIdStr.isBlank()) {
      try {
        UUID unitId = UUID.fromString(unitIdStr.trim());
        Optional<UUID> headOpt = hierarchy.resolveDepartmentHeadUserId(unitId, effectiveDate);
        return headOpt
            .map(u -> ParticipantResolutionResult.resolved(u, TYPE))
            .orElseGet(() -> ParticipantResolutionResult.vacant("Unit head position is vacant", TYPE));
      } catch (IllegalArgumentException ex) {
        return ParticipantResolutionResult.failed("Invalid unitId: " + unitIdStr, TYPE);
      }
    }

    UUID targetUser = context.subjectUserId() != null ? context.subjectUserId() : context.referenceUserId();
    String unitType = context.config().path("unitType").asText("DEPARTMENT");
    Optional<UUID> headOpt = hierarchy.resolveHeadOfUnitForUser(targetUser, unitType, effectiveDate);
    return headOpt
        .map(u -> ParticipantResolutionResult.resolved(u, TYPE))
        .orElseGet(() -> ParticipantResolutionResult.vacant("Unit head not found for user: " + targetUser, TYPE));
  }
}
