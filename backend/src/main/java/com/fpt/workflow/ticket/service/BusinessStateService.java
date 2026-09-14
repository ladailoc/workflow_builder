package com.fpt.workflow.ticket.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.repository.WorkflowStateDefinitionRepository;
import com.fpt.workflow.runtime.activation.BusinessStateTransitionPort;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/**
 * Drives the user-facing Workflow business state from runtime activation. Technical lifecycle
 * (EventStatus/NodeExecutionStatus/TaskExecutionStatus) is never equated with business state: only
 * a node that explicitly declares config.businessStateKey moves the display state, and every such
 * key must be declared by the exact pinned WorkflowVersion.
 */
@Service
public class BusinessStateService implements BusinessStateTransitionPort {
  private final JdbcTemplate jdbc; private final WorkflowStateDefinitionRepository states;
  private final UuidGenerator uuids; private final PlatformClock clock; private final ObjectMapper mapper;
  public BusinessStateService(JdbcTemplate jdbc,WorkflowStateDefinitionRepository states,UuidGenerator uuids,PlatformClock clock,ObjectMapper mapper){this.jdbc=jdbc;this.states=states;this.uuids=uuids;this.clock=clock;this.mapper=mapper;}

  @Override @Transactional(propagation=Propagation.MANDATORY)
  public void transitionIfChanged(UUID ticketId,UUID eventId,UUID workflowVersionId,String stateKey,UUID sourceNodeExecutionId){
    transition(ticketId,eventId,workflowVersionId,stateKey,sourceNodeExecutionId,null);
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void transition(UUID ticketId,UUID eventId,UUID workflowVersionId,String stateKey,UUID sourceNodeExecutionId,JsonNode metadata){
    boolean declared=states.findAllByWorkflowVersionIdOrderByDisplayOrderAsc(workflowVersionId).stream().anyMatch(s->s.getStateKey().equals(stateKey));
    if(!declared)throw new IllegalArgumentException("WORKFLOW_STATE_NOT_DECLARED: "+stateKey);
    // Replay guard: an already-open interval for the same state on this Event is a no-op, so an
    // idempotent runtime action can never append a duplicate history row.
    Integer open=jdbc.queryForObject("SELECT COUNT(*) FROM ticket_state_history WHERE ticket_id=? AND event_id=? AND state_key=? AND exited_at IS NULL",Integer.class,ticketId,eventId,stateKey);
    String current=jdbc.query("SELECT current_business_state_key FROM tickets WHERE id=?",rs->rs.next()?rs.getString(1):null,ticketId);
    if(open!=null&&open>0&&stateKey.equals(current))return;
    Instant now=clock.now();
    jdbc.update("UPDATE ticket_state_history SET exited_at=? WHERE ticket_id=? AND event_id=? AND exited_at IS NULL",java.sql.Timestamp.from(now),ticketId,eventId);
    int updated=jdbc.update("UPDATE tickets SET current_business_state_key=?, current_state_updated_at=?, updated_at=?, lock_version=lock_version+1 WHERE id=?",stateKey,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now),ticketId);
    if(updated!=1)throw new IllegalArgumentException("Ticket not found: "+ticketId);
    jdbc.update("INSERT INTO ticket_state_history(id,ticket_id,event_id,state_key,source_node_execution_id,entered_at,metadata_json) VALUES (?,?,?,?,?,?,?::jsonb)",uuids.generate(),ticketId,eventId,stateKey,sourceNodeExecutionId,java.sql.Timestamp.from(now),json(metadata));
  }
  private String json(JsonNode metadata){try{return mapper.writeValueAsString(metadata==null?mapper.createObjectNode():metadata);}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalArgumentException("Invalid state metadata",ex);}}
}
