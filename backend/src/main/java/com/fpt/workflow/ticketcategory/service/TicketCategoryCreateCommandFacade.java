package com.fpt.workflow.ticketcategory.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.operations.command.*;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.ticketcategory.dto.TicketCategoryDtos;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
@Service
public class TicketCategoryCreateCommandFacade {
  private final CommandExecutor commands;private final TicketCategoryTicketService service;private final TicketCategoryRepository categories;private final ObjectMapper mapper;private final CanonicalDefinitionJson canonical;
  public TicketCategoryCreateCommandFacade(CommandExecutor commands,TicketCategoryTicketService service,TicketCategoryRepository categories,ObjectMapper mapper,CanonicalDefinitionJson canonical){this.commands=commands;this.service=service;this.categories=categories;this.mapper=mapper;this.canonical=canonical;}
  public TicketCategoryDtos.CreatedTicket create(String key,CommandId commandId,TicketCategoryDtos.CreateTicket request){
    var category=categories.findByKey(key).orElseThrow(()->new com.fpt.workflow.shared.api.ResourceNotFoundException("TICKET_CATEGORY_NOT_FOUND","Business intent was not found"));
    var result=commands.execute(new CommandInvocation("TICKET_CATEGORY",category.getId(),commandId,"CREATE_TICKET",null,hash(request)),()->new CommandCompletion(mapper.valueToTree(service.create(key,request,commandId.toString())),mapper.createObjectNode()));
    try{return mapper.treeToValue(result.resultJson(),TicketCategoryDtos.CreatedTicket.class);}catch(JsonProcessingException ex){throw new IllegalStateException("Stored create-ticket result cannot be decoded",ex);}
  }
  private String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.canonicalize(mapper.valueToTree(value)).toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
