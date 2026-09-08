package com.fpt.workflow.runtime.activation;

import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import org.springframework.stereotype.Component;

/** P0 hook; concrete resolver snapshots are activated by later resolver command services. */
@Component
public final class NoOpParticipantActivationHook implements ParticipantActivationHook {

  @Override
  public void onActivation(
      Event event, NodeDefinition node, NodeExecution execution, EventContext eventContext) {
    // Deliberately lazy and scoped to this occurrence; never resolves all Event participants.
  }
}
