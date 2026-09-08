package com.fpt.workflow.integration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.integration.service.CallbackCommand;
import com.fpt.workflow.integration.service.CallbackCorrelationService;
import com.fpt.workflow.integration.service.CallbackProcessingResult;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/callbacks")
public class IntegrationCallbackController {

  private static final Logger log = LoggerFactory.getLogger(IntegrationCallbackController.class);

  private final CallbackCorrelationService callbackService;
  private final ObjectMapper objectMapper;
  private final PlatformClock clock;
  private final UuidGenerator uuidGenerator;

  public IntegrationCallbackController(
      CallbackCorrelationService callbackService,
      ObjectMapper objectMapper,
      PlatformClock clock,
      UuidGenerator uuidGenerator) {
    this.callbackService = Objects.requireNonNull(callbackService, "callbackService");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.uuidGenerator = Objects.requireNonNull(uuidGenerator, "uuidGenerator");
  }

  @PostMapping(value = "/{callbackCorrelationId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> handleCallback(
      @PathVariable("callbackCorrelationId") String callbackCorrelationId,
      @RequestHeader(value = "X-Connector-Key", required = false) String connectorKeyHeader,
      @RequestHeader(value = "X-External-Event-Id", required = false) String externalEventIdHeader,
      @RequestHeader(value = "X-Signature", required = false) String signatureHeader,
      @RequestHeader(value = "X-Timestamp", required = false) String timestampHeader,
      @RequestBody(required = false) String rawBody) {

    JsonNode parsedPayload = parseJsonBody(rawBody);

    String connectorKey =
        connectorKeyHeader != null && !connectorKeyHeader.isBlank()
            ? connectorKeyHeader
            : parsedPayload.path("connectorKey").asText(null);

    String externalEventId =
        externalEventIdHeader != null && !externalEventIdHeader.isBlank()
            ? externalEventIdHeader
            : parsedPayload.path("externalEventId").asText(null);

    String signature =
        signatureHeader != null && !signatureHeader.isBlank()
            ? signatureHeader
            : parsedPayload.path("signature").asText(null);

    Instant timestamp = parseTimestamp(timestampHeader, parsedPayload);

    CallbackCommand command =
        new CallbackCommand(
            callbackCorrelationId,
            connectorKey,
            externalEventId,
            signature,
            timestamp,
            rawBody != null ? rawBody : "{}",
            parsedPayload,
            new CorrelationId(uuidGenerator.generate()),
            new CommandId(uuidGenerator.generate()));

    CallbackProcessingResult result = callbackService.processCallback(command);

    return switch (result.status()) {
      case ACCEPTED ->
          ResponseEntity.ok(
              Map.of(
                  "status", "ACCEPTED",
                  "callbackId", result.callbackId(),
                  "message", result.message()));
      case DUPLICATE ->
          ResponseEntity.ok(
              Map.of(
                  "status",
                  "DUPLICATE",
                  "callbackId",
                  result.callbackId() != null ? result.callbackId() : "",
                  "message",
                  result.message()));
      case LATE ->
          ResponseEntity.ok(
              Map.of(
                  "status",
                  "LATE",
                  "callbackId",
                  result.callbackId() != null ? result.callbackId() : "",
                  "message",
                  result.message()));
      case REJECTED -> mapRejectedResponse(result);
    };
  }

  private ResponseEntity<?> mapRejectedResponse(CallbackProcessingResult result) {
    String msg = result.message() != null ? result.message() : "Callback rejected";
    if (msg.contains("Invalid callback signature")) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("status", "REJECTED", "error", msg));
    }
    if (msg.contains("Wrong or unknown callbackCorrelationId")
        || msg.contains("Unknown correlation")) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("status", "REJECTED", "error", msg));
    }
    if (msg.contains("outside allowable tolerance window") || msg.contains("replay")) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("status", "REJECTED", "error", msg));
    }
    return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "error", msg));
  }

  private JsonNode parseJsonBody(String rawBody) {
    if (rawBody == null || rawBody.isBlank()) {
      return JsonNodeFactory.instance.objectNode();
    }
    try {
      return objectMapper.readTree(rawBody);
    } catch (Exception e) {
      return JsonNodeFactory.instance.objectNode();
    }
  }

  private Instant parseTimestamp(String timestampHeader, JsonNode parsedPayload) {
    String value = timestampHeader;
    if (value == null || value.isBlank()) {
      value = parsedPayload.path("timestamp").asText(null);
    }
    if (value == null || value.isBlank()) {
      return clock.now();
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ex) {
      try {
        long epoch = Long.parseLong(value);
        return epoch > 1_000_000_000_000L
            ? Instant.ofEpochMilli(epoch)
            : Instant.ofEpochSecond(epoch);
      } catch (NumberFormatException ex2) {
        return null;
      }
    }
  }
}
