package com.fpt.workflow.ticket.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.operations.command.CommandCompletion;
import com.fpt.workflow.operations.command.CommandExecutor;
import com.fpt.workflow.operations.command.CommandInvocation;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.ticket.dto.TicketDtos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TicketCommandFacade {

  private final CommandExecutor commandExecutor;
  private final TicketService ticketService;
  private final ObjectMapper objectMapper;
  private final CanonicalDefinitionJson canonicalJson;

  public TicketCommandFacade(
      CommandExecutor commandExecutor,
      TicketService ticketService,
      ObjectMapper objectMapper,
      CanonicalDefinitionJson canonicalJson) {
    this.commandExecutor = commandExecutor;
    this.ticketService = ticketService;
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
  }

  public TicketDtos.AggregateView createDraft(CommandId commandId, TicketDtos.CreateDraft request) {
    return execute(
        new CommandInvocation(
            "REQUEST_TYPE",
            request.requestTypeId(),
            commandId,
            "TICKET_DRAFT_CREATE",
            null,
            hash(request)),
        () -> ticketService.createDraft(request));
  }

  public TicketDtos.AggregateView updateDraft(
      UUID ticketId,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      TicketDtos.UpdateDraft request) {
    return execute(
        new CommandInvocation(
            "TICKET",
            ticketId,
            commandId,
            "TICKET_DRAFT_UPDATE",
            expectedVersion.value(),
            hash(request)),
        () -> ticketService.updateDraft(ticketId, expectedVersion, request));
  }

  public TicketDtos.AggregateView submit(
      UUID ticketId,
      CommandId commandId,
      ExpectedVersion expectedVersion,
      TicketDtos.Submit request) {
    return execute(
        new CommandInvocation(
            "TICKET", ticketId, commandId, "TICKET_SUBMIT", expectedVersion.value(), hash(request)),
        () -> ticketService.submit(ticketId, expectedVersion, request, commandId));
  }

  private TicketDtos.AggregateView execute(
      CommandInvocation invocation, java.util.function.Supplier<TicketDtos.AggregateView> action) {
    var result =
        commandExecutor.execute(
            invocation,
            () ->
                new CommandCompletion(
                    objectMapper.valueToTree(action.get()), objectMapper.createObjectNode()));
    try {
      return objectMapper.treeToValue(result.resultJson(), TicketDtos.AggregateView.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored Ticket command result cannot be decoded", exception);
    }
  }

  private String hash(Object request) {
    JsonNode canonical = canonicalJson.canonicalize(objectMapper.valueToTree(request));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }
}
