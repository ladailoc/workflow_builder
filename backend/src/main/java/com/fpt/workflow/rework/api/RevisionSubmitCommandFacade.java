package com.fpt.workflow.rework.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.operations.command.*;
import com.fpt.workflow.rework.dto.RevisionRequestDtos;
import com.fpt.workflow.rework.service.RevisionRequestService;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Command-wrapped REQUEST_REVISION submission (v2.4.1): commandId idempotency, If-Match expected
 * version, audit and replay-stable result per the existing command architecture.
 */
@Service
public class RevisionSubmitCommandFacade {
  private final CommandExecutor commands;
  private final RevisionRequestService revisionRequests;
  private final ObjectMapper mapper;
  private final CanonicalDefinitionJson canonical;

  public RevisionSubmitCommandFacade(
      CommandExecutor commands,
      RevisionRequestService revisionRequests,
      ObjectMapper mapper,
      CanonicalDefinitionJson canonical) {
    this.commands = commands;
    this.revisionRequests = revisionRequests;
    this.mapper = mapper;
    this.canonical = canonical;
  }

  public RevisionRequestDtos.RevisionSubmitView submit(
      UUID requestId,
      JsonNode values,
      JsonNode replacementTicketData,
      String changeReason,
      long expectedVersion,
      CorrelationId correlationId,
      CommandId commandId) {
    Map<String, JsonNode> supplied = new HashMap<>();
    if (values != null && values.isObject()) {
      values.properties().forEach(entry -> supplied.put(entry.getKey(), entry.getValue()));
    }
    com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
    payload.set("values", values == null ? mapper.nullNode() : values);
    payload.set(
        "replacementTicketData",
        replacementTicketData == null ? mapper.nullNode() : replacementTicketData);
    payload.put("changeReason", changeReason == null ? "" : changeReason);
    var result =
        commands.execute(
            new CommandInvocation(
                "REVISION_REQUEST",
                requestId,
                commandId,
                "SUBMIT_REVISION",
                expectedVersion,
                hash(payload)),
            () -> {
              revisionRequests.submit(
                  requestId,
                  supplied,
                  replacementTicketData,
                  changeReason,
                  expectedVersion,
                  correlationId,
                  commandId);
              var stored = revisionRequests.require(requestId);
              return new CommandCompletion(
                  mapper.valueToTree(revisionRequests.view(stored)), mapper.createObjectNode());
            });
    try {
      return mapper.treeToValue(result.resultJson(), RevisionRequestDtos.RevisionSubmitView.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored revision submit result cannot be decoded", ex);
    }
  }

  private String hash(JsonNode value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      canonical.canonicalize(value).toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
