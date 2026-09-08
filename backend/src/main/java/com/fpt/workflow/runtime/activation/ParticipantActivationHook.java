package com.fpt.workflow.runtime.activation;

import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;

/** Resolver extension invoked lazily only when a participant-capable node activates. */
@FunctionalInterface
public interface ParticipantActivationHook {

  void onActivation(
      Event event, NodeDefinition node, NodeExecution execution, EventContext eventContext);
}
