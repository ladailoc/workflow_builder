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

  /** P2-19: event timeline with deterministic order (sensitive context masked by the service). */
  @GetMapping("/{eventId}/timeline")
  @PreAuthorize("isAuthenticated()")
  public java.util.List<EventMonitoringService.TimelineEntry> timeline(
      @PathVariable UUID eventId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "200") int size) {
    return service.timeline(eventId, page, size);
  }

  /** P2-19: event graph (version-pinned nodes/edges + occurrence overlay). */
  @GetMapping("/{eventId}/graph")
  @PreAuthorize("isAuthenticated()")
  public EventMonitoringService.GraphView graph(@PathVariable UUID eventId) {
    return service.graph(eventId);
  }

  /**
   * P2-19: safe event context. {@code view=safe} (default) masks sensitive values; any other
   * view value is rejected rather than exposing unmasked data.
   */
  @GetMapping("/{eventId}/context")
  @PreAuthorize("isAuthenticated()")
  public com.fasterxml.jackson.databind.JsonNode context(
      @PathVariable UUID eventId, @RequestParam(defaultValue = "safe") String view) {
    if (!"safe".equalsIgnoreCase(view)) {
      throw new IllegalArgumentException("Only view=safe is supported for event context");
    }
    return service.safeContext(eventId);
  }
}
