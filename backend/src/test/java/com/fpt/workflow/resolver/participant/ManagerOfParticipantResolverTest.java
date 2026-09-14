package com.fpt.workflow.resolver.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.organization.service.ManagerNotFoundException;
import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManagerOfParticipantResolverTest {

  private final OrganizationHierarchyService hierarchy = mock(OrganizationHierarchyService.class);
  private final ManagerOfParticipantResolver resolver = new ManagerOfParticipantResolver(hierarchy);

  @Test
  @DisplayName("Resolves manager at configured depth when found")
  void resolvesManagerWhenFound() {
    UUID creatorId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID managerId = UUID.randomUUID();
    ObjectNode config = JsonNodeFactory.instance.objectNode().put("type", "MANAGER_OF").put("depth", 1);
    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, targetUserId, null, config, Instant.parse("2026-09-10T12:00:00Z"));

    when(hierarchy.resolveManagerAtDepth(eq(targetUserId), eq(1), any())).thenReturn(managerId);

    UUID resolved = resolver.resolve(context);
    assertThat(resolved).isEqualTo(managerId);
  }

  @Test
  @DisplayName("Throws ManagerNotFoundException instead of falling back to creator when manager is not found")
  void throwsWhenManagerNotFoundWithoutFallingBackToCreator() {
    UUID creatorId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    ObjectNode config = JsonNodeFactory.instance.objectNode().put("type", "MANAGER_OF").put("depth", 2);
    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, targetUserId, null, config, Instant.parse("2026-09-10T12:00:00Z"));

    when(hierarchy.resolveManagerAtDepth(eq(targetUserId), eq(2), any()))
        .thenThrow(new ManagerNotFoundException("Manager not found at depth 2"));

    assertThatThrownBy(() -> resolver.resolve(context))
        .isInstanceOf(ManagerNotFoundException.class)
        .hasMessageContaining("depth 2");
  }

  @Test
  @DisplayName("Rejects non-positive depth")
  void rejectsNonPositiveDepth() {
    UUID creatorId = UUID.randomUUID();
    ObjectNode config = JsonNodeFactory.instance.objectNode().put("type", "MANAGER_OF").put("depth", 0);
    ParticipantResolverContext context =
        new ParticipantResolverContext(creatorId, creatorId, null, config, Instant.now());

    assertThatThrownBy(() -> resolver.resolve(context))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("depth must be positive");
  }
}
