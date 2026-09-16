package com.fpt.workflow.ticketcategory.dto;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
public final class TicketCategoryDtos {
  private TicketCategoryDtos(){}
  public record CreateTicket(JsonNode formData,String categoryChecksum,UUID formVersionId,String formChecksum,String mappingChecksum,UUID tenantId){
    public CreateTicket(JsonNode formData,String categoryChecksum,UUID formVersionId,String formChecksum,String mappingChecksum){this(formData,categoryChecksum,formVersionId,formChecksum,mappingChecksum,null);}
  }
  public record CreatedTicket(UUID ticketId,UUID eventId,UUID categoryVersionId,UUID formSubmissionId,UUID workflowVersionId,JsonNode inputs,String businessState,Instant createdAt){}
}
