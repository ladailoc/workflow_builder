package com.fpt.workflow.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.domain.FormVersion;
import com.fpt.workflow.form.repository.FormVersionRepository;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.visibility.VisibilityResolver;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import com.fpt.workflow.ticket.domain.Ticket;
import com.fpt.workflow.ticket.dto.TicketDtos;
import com.fpt.workflow.ticket.repository.TicketRepository;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryVersion;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryVersionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Safe, catalog-gated query surface for opted-in dynamic Ticket fields. */
@Service
public class TicketReportingQueryService {
  private static final int MAX_PAGE_SIZE = 100;
  private static final String CANDIDATE_SQL =
      "SELECT t.id FROM tickets t "
          + "JOIN events e ON e.ticket_id=t.id "
          + "WHERE e.workflow_version_id=? ORDER BY t.created_at DESC,t.id DESC";

  private final JdbcTemplate jdbc;
  private final WorkflowFormRepository forms;
  private final ReportableFieldCatalog catalog;
  private final TicketRepository tickets;
  private final VisibilityResolver visibility;
  private final ActorContextProvider actors;
  private final FormVersionRepository formVersions;
  private final TicketCategoryVersionRepository categoryVersions;

  @org.springframework.beans.factory.annotation.Autowired
  public TicketReportingQueryService(
      JdbcTemplate jdbc,
      WorkflowFormRepository forms,
      ReportableFieldCatalog catalog,
      TicketRepository tickets,
      VisibilityResolver visibility,
      ActorContextProvider actors,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          FormVersionRepository formVersions,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          TicketCategoryVersionRepository categoryVersions) {
    this.jdbc = jdbc;
    this.forms = forms;
    this.catalog = catalog;
    this.tickets = tickets;
    this.visibility = visibility;
    this.actors = actors;
    this.formVersions = formVersions;
    this.categoryVersions = categoryVersions;
  }

  public TicketReportingQueryService(
      JdbcTemplate jdbc,
      WorkflowFormRepository forms,
      ReportableFieldCatalog catalog,
      TicketRepository tickets,
      VisibilityResolver visibility,
      ActorContextProvider actors) {
    this(jdbc, forms, catalog, tickets, visibility, actors, null, null);
  }

  @TransactionalQuery
  @PreAuthorize("isAuthenticated()")
  public SearchPage search(SearchRequest request) {
    validatePage(request);
    JsonNode schema = ticketFormSchema(request.workflowVersionId());
    ReportableFieldCatalog.FieldProjection field = requireQueryableField(schema, request);
    Object expected = parse(field.canonicalType(), request.value());
    ActorContext actor = actors.requireActor();

    List<UUID> orderedIds =
        jdbc.query(
            CANDIDATE_SQL,
            (result, row) -> result.getObject(1, UUID.class),
            request.workflowVersionId().toString());
    Map<UUID, Ticket> byId = new HashMap<>();
    tickets.findAllById(orderedIds).forEach(ticket -> byId.put(ticket.getId(), ticket));

    List<Ticket> matches = new ArrayList<>();
    for (UUID id : orderedIds) {
      Ticket ticket = byId.get(id);
      if (ticket != null
          && visibility.mayViewTicket(actor, ticket.getId())
          && matches(ticket.getDataJson().path(field.fieldKey()), field, request.operator(), expected)) {
        matches.add(ticket);
      }
    }
    // Preserve a deterministic tie-breaker even if a repository/driver changes row ordering.
    matches.sort(
        Comparator.comparing(Ticket::getCreatedAt)
            .reversed()
            .thenComparing(Ticket::getId, Comparator.reverseOrder()));
    long requestedOffset = (long) request.page() * request.size();
    int from = (int) Math.min(requestedOffset, matches.size());
    int to = Math.min(from + request.size(), matches.size());
    List<TicketDtos.TicketView> items =
        matches.subList(from, to).stream().map(TicketDtos.TicketView::from).toList();
    return new SearchPage(request.page(), request.size(), matches.size(), items);
  }

  @TransactionalQuery
  @PreAuthorize("isAuthenticated()")
  public List<ReportableFieldCatalog.FieldProjection> fields(UUID workflowVersionId) {
    return catalog.fields(ticketFormSchema(workflowVersionId));
  }

  private JsonNode ticketFormSchema(UUID workflowVersionId) {
    if (workflowVersionId == null) invalid("workflowVersionId is required");
    if (formVersions != null && categoryVersions != null) {
      for (TicketCategoryVersion categoryVersion :
          categoryVersions.findAllByWorkflowVersionId(workflowVersionId)) {
        if (!Set.of("PUBLISHED", "SUPERSEDED").contains(categoryVersion.getStatus())) continue;
        FormVersion formVersion = formVersions.findById(categoryVersion.getFormVersionId()).orElse(null);
        if (formVersion != null && Set.of("PUBLISHED", "SUPERSEDED").contains(formVersion.getStatus())) {
          JsonNode schema = formVersion.getCompiledSchemaJson();
          return schema != null ? schema : formVersion.getSchemaJson();
        }
      }
    }
    return forms.findAllByWorkflowVersionIdOrderByFormKeyAsc(workflowVersionId).stream()
        .filter(form -> form.getFormType() == WorkflowFormType.TICKET_FORM)
        .map(WorkflowForm::getSchemaJson)
        .findFirst()
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "REPORTING.FORM_NOT_FOUND", "Ticket form was not found"));
  }

  private ReportableFieldCatalog.FieldProjection requireQueryableField(
      JsonNode schema, SearchRequest request) {
    if (request.fieldKey() == null || request.fieldKey().isBlank() || request.operator() == null) {
      throw invalid("fieldKey and operator are required");
    }
    ReportableFieldCatalog.FieldProjection field =
        catalog.fields(schema).stream()
            .filter(candidate -> candidate.fieldKey().equals(request.fieldKey()))
            .findFirst()
            .orElseThrow(
                () ->
                    new UnprocessableCommandException(
                        "REPORTING.FIELD_NOT_QUERYABLE",
                        "Field is not explicitly searchable, filterable, or reportable"));
    boolean allowed =
        request.operator() == Operator.CONTAINS
            ? field.searchable()
            : field.filterable() || field.reportable();
    if (!allowed) {
      throw new UnprocessableCommandException(
          "REPORTING.OPERATOR_NOT_ALLOWED", "Operator is not enabled for this field");
    }
    return field;
  }

  private static boolean matches(
      JsonNode actualNode,
      ReportableFieldCatalog.FieldProjection field,
      Operator operator,
      Object expected) {
    if (actualNode.isMissingNode() || actualNode.isNull()) return false;
    Object actual;
    try {
      actual = parse(field.canonicalType(), actualNode.isTextual() ? actualNode.asText() : actualNode.toString());
    } catch (UnprocessableCommandException ignored) {
      return false;
    }
    if (operator == Operator.CONTAINS) {
      return actual.toString().toLowerCase(Locale.ROOT)
          .contains(expected.toString().toLowerCase(Locale.ROOT));
    }
    @SuppressWarnings("unchecked")
    int comparison = ((Comparable<Object>) actual).compareTo(expected);
    return switch (operator) {
      case EQ -> comparison == 0;
      case GT -> comparison > 0;
      case GTE -> comparison >= 0;
      case LT -> comparison < 0;
      case LTE -> comparison <= 0;
      case CONTAINS -> false;
    };
  }

  private static Object parse(String type, String value) {
    if (value == null) throw invalid("filter value is required");
    try {
      return switch (type.toUpperCase(Locale.ROOT)) {
        case "NUMBER", "INTEGER", "MONEY" -> new BigDecimal(value);
        case "BOOLEAN" -> {
          if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw invalid("boolean filter must be true or false");
          }
          yield Boolean.valueOf(value);
        }
        case "DATE" -> LocalDate.parse(value);
        case "DATETIME" -> Instant.parse(value);
        case "OBJECT", "ARRAY", "FILE_REF", "FILE_LIST" ->
            throw invalid("complex field type is not queryable");
        default -> value;
      };
    } catch (NumberFormatException | DateTimeParseException exception) {
      throw invalid("filter value does not match field type " + type);
    }
  }

  private static void validatePage(SearchRequest request) {
    if (request == null) throw invalid("search request is required");
    if (request.page() < 0 || request.size() <= 0 || request.size() > MAX_PAGE_SIZE) {
      throw invalid("page must be non-negative and size must be between 1 and 100");
    }
  }

  private static UnprocessableCommandException invalid(String message) {
    return new UnprocessableCommandException("REPORTING.INVALID_FILTER", message);
  }

  public enum Operator {
    EQ,
    CONTAINS,
    GT,
    GTE,
    LT,
    LTE
  }

  public record SearchRequest(
      UUID workflowVersionId, String fieldKey, Operator operator, String value, int page, int size) {}

  public record SearchPage(int page, int size, long totalElements, List<TicketDtos.TicketView> items) {
    public SearchPage {
      items = List.copyOf(items);
    }
  }
}
