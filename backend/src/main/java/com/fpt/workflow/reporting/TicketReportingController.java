package com.fpt.workflow.reporting;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports/tickets")
public class TicketReportingController {
  private final TicketReportingQueryService queries;

  public TicketReportingController(TicketReportingQueryService queries) {
    this.queries = queries;
  }

  @GetMapping
  public TicketReportingQueryService.SearchPage search(
      @RequestParam UUID workflowVersionId,
      @RequestParam String fieldKey,
      @RequestParam TicketReportingQueryService.Operator operator,
      @RequestParam String value,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return queries.search(
        new TicketReportingQueryService.SearchRequest(
            workflowVersionId, fieldKey, operator, value, page, size));
  }

  @GetMapping("/fields")
  public List<ReportableFieldCatalog.FieldProjection> fields(
      @RequestParam UUID workflowVersionId) {
    return queries.fields(workflowVersionId);
  }
}
