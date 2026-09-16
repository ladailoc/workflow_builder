package com.fpt.workflow.slanotification.api;

import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.slanotification.domain.NotificationDispatch;
import com.fpt.workflow.slanotification.repository.NotificationDispatchRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only inbox projection for the currently authenticated actor. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
  private final NotificationDispatchRepository dispatches;
  private final ActorContextProvider actors;

  public NotificationController(
      NotificationDispatchRepository dispatches, ActorContextProvider actors) {
    this.dispatches = dispatches;
    this.actors = actors;
  }

  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public List<NotificationView> list() {
    UUID actorId = actors.requireActor().actorId();
    return dispatches.findAllByRecipientUserIdOrderByCreatedAtDesc(actorId).stream()
        .map(NotificationView::from)
        .toList();
  }

  public record NotificationView(
      UUID id,
      UUID eventId,
      UUID taskId,
      String channel,
      String status,
      String dedupKey,
      com.fasterxml.jackson.databind.JsonNode template,
      com.fasterxml.jackson.databind.JsonNode payload,
      Instant createdAt,
      Instant sentAt) {
    static NotificationView from(NotificationDispatch value) {
      return new NotificationView(
          value.getId(),
          value.getEventId(),
          value.getTaskId(),
          value.getChannel(),
          value.getStatus().name(),
          value.getDedupKey(),
          value.getTemplateSnapshotJson(),
          value.getPayloadJson(),
          value.getCreatedAt(),
          value.getSentAt());
    }
  }
}
