package com.fpt.workflow.ticketcategory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.operations.command.CommandCompletion;
import com.fpt.workflow.operations.command.CommandExecutor;
import com.fpt.workflow.operations.command.CommandInvocation;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.ticketcategory.dto.TicketCategoryBindingDtos;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TicketCategoryBindingCommandFacade {
  private final CommandExecutor commands;
  private final TicketCategoryBindingService service;
  private final TicketCategoryRepository categories;
  private final ObjectMapper mapper;
  private final CanonicalDefinitionJson canonical;

  public TicketCategoryBindingCommandFacade(
      CommandExecutor commands,
      TicketCategoryBindingService service,
      TicketCategoryRepository categories,
      ObjectMapper mapper,
      CanonicalDefinitionJson canonical) {
    this.commands = commands;
    this.service = service;
    this.categories = categories;
    this.mapper = mapper;
    this.canonical = canonical;
  }

  public TicketCategoryBindingDtos.ScopeBindingView upsert(
      UUID categoryId,
      CommandId commandId,
      TicketCategoryBindingService.OverrideCommand command) {
    categories.findById(categoryId).orElseThrow(() -> new IllegalArgumentException("TicketCategory not found: " + categoryId));
    var result = commands.execute(
        new CommandInvocation("TICKET_CATEGORY", categoryId, commandId, "UPSERT_TENANT_WORKFLOW_BINDING", command.expectedLockVersion(), hash(command)),
        () -> new CommandCompletion(mapper.valueToTree(service.upsertOverride(categoryId, command)), mapper.createObjectNode()));
    try {
      return mapper.treeToValue(result.resultJson(), TicketCategoryBindingDtos.ScopeBindingView.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored tenant binding result cannot be decoded", ex);
    }
  }

  public void delete(UUID categoryId, UUID tenantId, CommandId commandId) {
    commands.execute(
        new CommandInvocation("TICKET_CATEGORY", categoryId, commandId, "DELETE_TENANT_WORKFLOW_BINDING", null, hash(new DeleteRequest(tenantId))),
        () -> {
          service.deleteOverride(categoryId, tenantId);
          return new CommandCompletion(mapper.createObjectNode(), mapper.createObjectNode());
        });
  }

  private String hash(Object value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(
          canonical.canonicalize(mapper.valueToTree(value)).toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private record DeleteRequest(UUID tenantId) {}
}
