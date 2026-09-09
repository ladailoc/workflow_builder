package com.fpt.workflow.monitoring;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
public class EventMonitoringController {
  private final EventMonitoringService service;

  public EventMonitoringController(EventMonitoringService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public java.util.List<EventMonitoringService.EventSummaryView> list() {
    return service.listEvents();
  }

  @GetMapping("/{eventId}/monitoring")
  @PreAuthorize("isAuthenticated()")
  public EventMonitoringService.EventMonitoringView get(@PathVariable UUID eventId) {
    return service.get(eventId);
  }
}
