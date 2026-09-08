package com.fpt.workflow.slanotification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.fpt.workflow.integration.service.PayloadSanitizer;
import com.fpt.workflow.resolver.participant.*;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.slanotification.domain.NotificationDispatch;
import com.fpt.workflow.ticket.repository.TicketRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  private final EventRepository events;
  private final TicketRepository tickets;
  private final ParticipantResolverRegistry resolvers;
  private final NotificationTransactions transactions;
  private final PlatformClock clock;

  public NotificationService(
      EventRepository events,
      TicketRepository tickets,
      ParticipantResolverRegistry resolvers,
      NotificationTransactions transactions,
      PlatformClock clock) {
    this.events = events;
    this.tickets = tickets;
    this.resolvers = resolvers;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Transactional
  public NotificationDispatch dispatch(NotificationRequest request) {
    var event = events.findById(request.eventId()).orElseThrow();
    var ticket = tickets.findById(event.getTicketId()).orElseThrow();
    JsonNode config = object(request.participantConfig(), "participantConfig");
    String resolverType = config.path("type").asText();
    UUID reference =
        request.referenceUserId() == null ? ticket.getCreatorId() : request.referenceUserId();
    UUID recipient =
        resolvers.resolve(
            resolverType,
            new ParticipantResolverContext(
                ticket.getCreatorId(), reference, request.item(), config, clock.now()));
    ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
    snapshot.put("resolverType", resolverType);
    snapshot.put("resolvedUserId", recipient.toString());
    snapshot.put("resolvedAt", clock.now().toString());
    snapshot.set("config", PayloadSanitizer.sanitize(config));
    JsonNode template =
        object(PayloadSanitizer.sanitize(request.templateSnapshot()), "templateSnapshot");
    JsonNode payload = object(PayloadSanitizer.sanitize(request.payload()), "payload");
    return transactions.create(
        event.getId(),
        request.nodeExecutionId(),
        request.taskId(),
        required(request.channel()),
        recipient,
        snapshot,
        template,
        payload,
        required(request.dedupKey()),
        request.maxAttempts(),
        request.allowAfterTerminal());
  }

  private static JsonNode object(JsonNode node, String name) {
    JsonNode value = node == null ? JsonNodeFactory.instance.objectNode() : node;
    if (!value.isObject()) throw new IllegalArgumentException(name + " must be an object");
    return value;
  }

  private static String required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");
    return value.trim();
  }
}
