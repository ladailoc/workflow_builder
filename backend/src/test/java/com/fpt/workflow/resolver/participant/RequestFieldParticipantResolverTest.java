package com.fpt.workflow.resolver.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestFieldParticipantResolverTest {
  private final EmployeeRepository employees = mock(EmployeeRepository.class);
  private final RequestFieldParticipantResolver resolver =
      new RequestFieldParticipantResolver(employees);

  @Test
  void validatesUsersAgainstTheOrganizationDirectoryAndDeduplicates() {
    UUID userId = UUID.randomUUID();
    Employee employee = mock(Employee.class);
    when(employee.isActive()).thenReturn(true);
    when(employees.findByUserId(userId)).thenReturn(Optional.of(employee));
    ObjectNode ticketData = JsonNodeFactory.instance.objectNode();
    ArrayNode users = ticketData.putArray("reviewers");
    users.add(userId.toString());
    users.add(userId.toString());

    var result = resolver.resolveResult(context(ticketData, "USER_LIST"));

    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(result.users()).containsExactly(userId);
  }

  @Test
  void rejectsMalformedIdentifiersInsteadOfSilentlyDroppingThem() {
    ObjectNode ticketData = JsonNodeFactory.instance.objectNode();
    ticketData.putArray("reviewers").add("not-a-uuid");

    var result = resolver.resolveResult(context(ticketData, "USER_LIST"));

    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.FAILED);
    assertThat(result.reason()).contains("invalid USER identifier");
  }

  @Test
  void reportsInactiveAndUnknownUsers() {
    UUID inactiveId = UUID.randomUUID();
    Employee inactive = mock(Employee.class);
    when(inactive.isActive()).thenReturn(false);
    when(employees.findByUserId(inactiveId)).thenReturn(Optional.of(inactive));
    ObjectNode inactiveData = JsonNodeFactory.instance.objectNode();
    inactiveData.put("reviewers", inactiveId.toString());
    ObjectNode unknownData = JsonNodeFactory.instance.objectNode();
    unknownData.put("reviewers", UUID.randomUUID().toString());

    assertThat(resolver.resolveResult(context(inactiveData, "USER")).status())
        .isEqualTo(ParticipantResolutionStatus.INACTIVE_ASSIGNEE);
    assertThat(resolver.resolveResult(context(unknownData, "USER")).status())
        .isEqualTo(ParticipantResolutionStatus.NOT_FOUND);
  }

  @Test
  void rejectsAFieldWhoseDeclaredTypeIsNotParticipantCapable() {
    ObjectNode ticketData = JsonNodeFactory.instance.objectNode();
    ticketData.put("reviewers", UUID.randomUUID().toString());

    var result = resolver.resolveResult(context(ticketData, "TEXT"));

    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.FAILED);
    assertThat(result.reason()).contains("USER or USER_LIST");
  }

  private ParticipantResolverContext context(ObjectNode ticketData, String fieldType) {
    ObjectNode config = JsonNodeFactory.instance.objectNode();
    config.put("type", "REQUEST_FIELD");
    config.put("field", "reviewers");
    config.put("fieldType", fieldType);
    UUID creator = UUID.randomUUID();
    return new ParticipantResolverContext(
        creator,
        creator,
        null,
        config,
        Instant.parse("2026-09-12T00:00:00Z"),
        ticketData,
        JsonNodeFactory.instance.objectNode(),
        null);
  }
}
