package com.fpt.workflow.ticket.domain;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="ticket_state_history")
public class TicketStateHistory {
  @Id private UUID id; @Column(name="ticket_id",nullable=false) private UUID ticketId;
  @Column(name="event_id",nullable=false) private UUID eventId;
  @Column(name="state_key",nullable=false,length=128) private String stateKey;
  @Column(name="source_node_execution_id") private UUID sourceNodeExecutionId;
  @Column(name="entered_at",nullable=false) private Instant enteredAt;
  @Column(name="exited_at") private Instant exitedAt;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="metadata_json",nullable=false,columnDefinition="jsonb") private JsonNode metadataJson;
  protected TicketStateHistory(){}
  public UUID getId(){return id;} public UUID getTicketId(){return ticketId;} public UUID getEventId(){return eventId;}
  public String getStateKey(){return stateKey;} public UUID getSourceNodeExecutionId(){return sourceNodeExecutionId;}
  public Instant getEnteredAt(){return enteredAt;} public Instant getExitedAt(){return exitedAt;}
  public JsonNode getMetadataJson(){return metadataJson.deepCopy();}
}
