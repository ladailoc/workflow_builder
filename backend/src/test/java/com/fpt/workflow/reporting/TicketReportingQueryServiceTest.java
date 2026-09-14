package com.fpt.workflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.security.visibility.VisibilityResolver;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class TicketReportingQueryServiceTest {
  private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final WorkflowFormRepository forms = mock(WorkflowFormRepository.class);
  private final TicketRepository tickets = mock(TicketRepository.class);
  private final VisibilityResolver visibility = mock(VisibilityResolver.class);
  private final ActorContextProvider actors = mock(ActorContextProvider.class);
  private final TicketReportingQueryService service =
      new TicketReportingQueryService(
          jdbc, forms, new ReportableFieldCatalog(), tickets, visibility, actors);
  private final UUID versionId = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    WorkflowForm form =
        WorkflowForm.create(
            UUID.randomUUID(),
            versionId,
            "ticket",
            WorkflowFormType.TICKET_FORM,
            new ObjectMapper()
                .readTree(
                    """
                    {"fields":[
                      {"key":"amount","type":{"type":"MONEY","nullable":false},
                       "semantics":{"filterable":true,"reportable":true}},
                      {"key":"reference","type":{"type":"STRING","nullable":false},
                       "semantics":{"searchable":true}},
                      {"key":"privateNote","type":{"type":"STRING","nullable":true},
                       "semantics":{}}
                    ]}
                    """),
            "checksum");
    when(forms.findAllByWorkflowVersionIdOrderByFormKeyAsc(versionId)).thenReturn(List.of(form));
    when(actors.requireActor())
        .thenReturn(new ActorContext(ACTOR, "actor@test", Set.of(RoleKey.USER), Set.of()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void filtersVisibleTicketsBeforeStablePagination() {
    UUID requestType = UUID.randomUUID();
    Ticket newestUnrelated = ticket(requestType, UUID.randomUUID(), 99, "2026-01-03T00:00:00Z");
    Ticket newestVisible = ticket(requestType, ACTOR, 20, "2026-01-02T00:00:00Z");
    Ticket oldestVisible = ticket(requestType, ACTOR, 10, "2026-01-01T00:00:00Z");
    List<UUID> ordered =
        List.of(newestUnrelated.getId(), newestVisible.getId(), oldestVisible.getId());
    when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenReturn(ordered);
    when(tickets.findAllById(ordered))
        .thenReturn(List.of(oldestVisible, newestUnrelated, newestVisible));
    when(visibility.mayViewTicket(any(), any(UUID.class)))
        .thenAnswer(
            invocation ->
                !newestUnrelated.getId().equals(invocation.<UUID>getArgument(1)));

    var first =
        service.search(
            new TicketReportingQueryService.SearchRequest(
                versionId, "amount", TicketReportingQueryService.Operator.GTE, "10", 0, 1));
    var second =
        service.search(
            new TicketReportingQueryService.SearchRequest(
                versionId, "amount", TicketReportingQueryService.Operator.GTE, "10", 1, 1));

    assertThat(first.totalElements()).isEqualTo(2);
    assertThat(first.items()).extracting(item -> item.id()).containsExactly(newestVisible.getId());
    assertThat(second.items()).extracting(item -> item.id()).containsExactly(oldestVisible.getId());
  }

  @Test
  void rejectsNonReportableAndInjectionLikeFieldBeforeDatabaseQuery() {
    assertThatThrownBy(
            () ->
                service.search(
                    new TicketReportingQueryService.SearchRequest(
                        versionId,
                        "privateNote') OR TRUE --",
                        TicketReportingQueryService.Operator.EQ,
                        "anything",
                        0,
                        20)))
        .isInstanceOfSatisfying(
            UnprocessableCommandException.class,
            exception -> assertThat(exception.code()).isEqualTo("REPORTING.FIELD_NOT_QUERYABLE"));

    verifyNoInteractions(jdbc, tickets, visibility);
  }

  @Test
  void returnsStructuredErrorForValueThatDoesNotMatchFieldType() {
    assertThatThrownBy(
            () ->
                service.search(
                    new TicketReportingQueryService.SearchRequest(
                        versionId,
                        "amount",
                        TicketReportingQueryService.Operator.GT,
                        "not-a-number",
                        0,
                        20)))
        .isInstanceOfSatisfying(
            UnprocessableCommandException.class,
            exception -> assertThat(exception.code()).isEqualTo("REPORTING.INVALID_FILTER"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void veryLargePageDoesNotOverflowTheOffset() {
    when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenReturn(List.of());
    when(tickets.findAllById(List.<UUID>of())).thenReturn(List.of());

    var result =
        service.search(
            new TicketReportingQueryService.SearchRequest(
                versionId,
                "amount",
                TicketReportingQueryService.Operator.GTE,
                "10",
                Integer.MAX_VALUE,
                100));

    assertThat(result.items()).isEmpty();
    assertThat(result.totalElements()).isZero();
  }

  private static Ticket ticket(UUID requestType, UUID creator, int amount, String createdAt) {
    return Ticket.createDraft(
        UUID.randomUUID(),
        requestType,
        creator,
        new ObjectMapper().createObjectNode().put("amount", amount),
        Instant.parse(createdAt));
  }
}
