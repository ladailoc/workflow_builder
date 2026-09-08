package com.fpt.workflow.ticket.api;

import com.fpt.workflow.ticket.service.RequestCatalogService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/request-types")
public class RequestCatalogController {

  private final RequestCatalogService catalogService;

  public RequestCatalogController(RequestCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  @GetMapping
  public List<RequestCatalogService.CatalogItem> list() {
    return catalogService.list();
  }

  @GetMapping("/{key}/create-schema")
  public RequestCatalogService.CreateSchema createSchema(@PathVariable String key) {
    return catalogService.createSchema(key);
  }
}
